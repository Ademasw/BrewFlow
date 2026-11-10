package app.brewflow.order;

import app.brewflow.audit.AuditService;
import app.brewflow.common.ApiException;
import app.brewflow.common.AppMode;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.kds.TicketStreamHub.TicketChanged;
import app.brewflow.order.OrderViews.Order;
import app.brewflow.order.OrderWriter.IdempotencyRow;
import app.brewflow.order.OrderWriter.OpenShift;
import app.brewflow.order.QuoteRepository.QuoteRow;
import app.brewflow.security.StaffPrincipal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Section 4.2 normal submit. One transaction inserts the idempotency row, consumes the quote, inserts the order,
 * line snapshots, one payment_record and the audit event.
 */
@Service
public class OrderService {

    public record SubmitRequest(UUID quoteId, String paymentMethod, String externalReference,
                                Boolean confirmedExternalAction) {
    }

    public record SubmitResult(Order order, boolean created) {
    }

    public static final String SOURCE_SUBMIT = "SUBMIT";
    public static final String SOURCE_RECOVERY = "RECOVERY";

    private final OrderWriter writer;
    private final QuoteRepository quotes;
    private final OrderReadService reads;
    private final AppMode mode;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TimeSource time;

    public OrderService(OrderWriter writer, QuoteRepository quotes, OrderReadService reads, AppMode mode,
                        AuditService audit, ApplicationEventPublisher events, TimeSource time) {
        this.writer = writer;
        this.quotes = quotes;
        this.reads = reads;
        this.mode = mode;
        this.audit = audit;
        this.events = events;
        this.time = time;
    }

    @Transactional
    public SubmitResult submit(StaffPrincipal actor, UUID key, SubmitRequest req) {
        if (req.quoteId() == null) {
            throw ApiException.invalid("quoteId", "quoteId is required.");
        }
        PaymentMethod method = PaymentMethod.parse("paymentMethod", req.paymentMethod());
        if (!Boolean.TRUE.equals(req.confirmedExternalAction())) {
            throw new ApiException(ErrorCode.ATTESTATION_REQUIRED,
                    "Confirm that the external payment action was done before recording the order.");
        }
        String reference = OrderWriter.normalizeReference(req.externalReference());
        String hash = OrderWriter.requestHash(req.quoteId(), method, reference);

        writer.lockKey(actor.id(), key);
        Optional<IdempotencyRow> previous = writer.findKey(actor.id(), key);
        if (previous.isPresent()) {
            IdempotencyRow row = previous.get();
            Order order = reads.full(row.orderId()).orElseThrow();
            boolean sameBody = row.requestHash().equals(hash);
            // a key stored by admin recovery returns that order to the cashier's retry of the same quote
            boolean recoveredSameQuote = SOURCE_RECOVERY.equals(row.source()) && order.quoteId().equals(req.quoteId());
            if (sameBody || recoveredSameQuote) {
                return new SubmitResult(order, false);
            }
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different body.");
        }

        mode.requireAllowed(method);
        QuoteRow quote = quotes.lock(req.quoteId()).orElseThrow(ApiException::notFound);
        Optional<UUID> consumedBy = quotes.consumingOrder(quote.id());
        if (consumedBy.isPresent()) {
            Map<String, Object> details = quote.cashierId().equals(actor.id())
                    ? Map.of("orderId", consumedBy.get()) : Map.of();
            throw new ApiException(ErrorCode.QUOTE_ALREADY_CONSUMED,
                    "This quote already has an order. Do not repeat the fiscal action.", details);
        }
        if (!quote.cashierId().equals(actor.id()) && !actor.isAdmin()) {
            throw ApiException.notFound();
        }
        if (!quote.expiresAt().isAfter(time.now())) {
            throw new ApiException(ErrorCode.QUOTE_EXPIRED,
                    "The quote expired. Do not repeat the fiscal action. Ask a manager to recover this quote.",
                    Map.of("quoteRef", quote.quoteRef(), "quoteId", quote.id()));
        }
        OpenShift shift = writer.requireOpenShift();

        String recordStatus = mode.isLive() ? "RECORDED" : "TRAINING";
        UUID orderId = writer.create(quote, actor.id(), actor.id(), shift, recordStatus, method, reference, actor.id());
        writer.insertKey(actor.id(), key, hash, SOURCE_SUBMIT, orderId);
        audit.record(actor.id(), "ORDER_CREATED", "orders", orderId, Map.of(
                "quoteId", quote.id(), "recordStatus", recordStatus, "paymentMethod", method.name()));
        events.publishEvent(new TicketChanged(orderId, 0, "CREATED"));
        return new SubmitResult(reads.full(orderId).orElseThrow(), true);
    }

    /** Section 4.3: the creating cashier recovers by key; an admin may look up any key (after a staff switch). */
    @Transactional(readOnly = true)
    public Order byKey(StaffPrincipal viewer, UUID key) {
        Optional<UUID> orderId = viewer.isAdmin()
                ? writer.findAnyKey(key)
                : writer.findKey(viewer.id(), key).map(IdempotencyRow::orderId);
        return orderId.flatMap(reads::full).orElseThrow(ApiException::notFound);
    }
}
