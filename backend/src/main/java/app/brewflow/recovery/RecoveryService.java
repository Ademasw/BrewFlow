package app.brewflow.recovery;

import app.brewflow.audit.AuditService;
import app.brewflow.common.ApiException;
import app.brewflow.common.AppMode;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.Money;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.kds.TicketStreamHub.TicketChanged;
import app.brewflow.order.OrderReadService;
import app.brewflow.order.OrderService;
import app.brewflow.order.OrderViews.Order;
import app.brewflow.order.OrderWriter;
import app.brewflow.order.OrderWriter.OpenShift;
import app.brewflow.order.PaymentMethod;
import app.brewflow.order.QuoteRepository;
import app.brewflow.order.QuoteRepository.ExpiredUnconsumed;
import app.brewflow.order.QuoteRepository.QuoteRow;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Section 4.4 orphan-receipt recovery (ADR-008, ADR-016). An admin recovers an expired, unconsumed quote from its
 * frozen snapshot. Recovery is idempotent by quote id; the cashier's original key is optional evidence.
 */
@Service
public class RecoveryService {

    public static final String NO_SECOND_RECEIPT =
            "Do not issue another fiscal receipt. This order records the receipt that was already issued.";

    public record RecoverRequest(String attestedAmount, String paymentMethod, String externalReference, String reason,
                                 UUID idempotencyKey) {
    }

    public sealed interface Outcome permits Recovered, Mismatch {
    }

    public record Recovered(Order order, boolean created) implements Outcome {
    }

    public record Mismatch(UUID exceptionId, String snapshotTotal, String attestedAmount) implements Outcome {
    }

    private final QuoteRepository quotes;
    private final OrderWriter writer;
    private final OrderReadService reads;
    private final AppMode mode;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final TimeSource time;

    public RecoveryService(QuoteRepository quotes, OrderWriter writer, OrderReadService reads, AppMode mode,
                           AuditService audit, ApplicationEventPublisher events, JdbcClient jdbc, TimeSource time) {
        this.quotes = quotes;
        this.writer = writer;
        this.reads = reads;
        this.mode = mode;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public List<ExpiredUnconsumed> expiredUnconsumed(LocalDate businessDate) {
        return quotes.expiredUnconsumed(businessDate, time.now());
    }

    /**
     * Returns Mismatch without throwing so the PENDING exception row commits; the controller answers
     * 409 AMOUNT_MISMATCH.
     */
    @Transactional
    public Outcome recover(UUID adminId, UUID quoteId, RecoverRequest req) {
        if (!Money.isTwoDecimalString(req.attestedAmount())) {
            throw ApiException.invalid("attestedAmount", "attestedAmount is a string with exactly two decimals, like \"120.00\".");
        }
        PaymentMethod method = PaymentMethod.parse("paymentMethod", req.paymentMethod());
        String reference = OrderWriter.normalizeReference(req.externalReference());
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty() || reason.length() > 500) {
            throw ApiException.invalid("reason", "reason needs 1 to 500 characters.");
        }

        QuoteRow unlocked = quotes.find(quoteId).orElseThrow(ApiException::notFound);
        if (req.idempotencyKey() != null) {
            // same lock as the cashier's POST /orders with this key
            writer.lockKey(unlocked.cashierId(), req.idempotencyKey());
        }
        QuoteRow quote = quotes.lock(quoteId).orElseThrow(ApiException::notFound);

        Optional<UUID> consumedBy = quotes.consumingOrder(quoteId);
        if (consumedBy.isPresent()) {
            return new Recovered(reads.full(consumedBy.get()).orElseThrow(), false);
        }
        if (quote.expiresAt().isAfter(time.now())) {
            throw new ApiException(ErrorCode.QUOTE_STILL_VALID,
                    "This quote has not expired. The cashier can still submit it.", Map.of("expiresAt", quote.expiresAt()));
        }
        mode.requireAllowed(method);
        if (req.idempotencyKey() != null && writer.keyUsedByAnyone(req.idempotencyKey())) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "This idempotency key already created an order.");
        }

        String snapshotTotal = Money.format(quote.total());
        if (!snapshotTotal.equals(req.attestedAmount())) {
            UUID exceptionId = UUID.randomUUID();
            jdbc.sql("""
                            insert into external_action_exception (id, quote_id, cashier_id, idempotency_key, attested_amount,
                                snapshot_total, payment_method, state, reason, created_by, created_at)
                            values (:id, :quote, :cashier, :key, :attested, :snapshot, :method, 'PENDING', :reason, :by, :at)""")
                    .param("id", exceptionId).param("quote", quoteId).param("cashier", quote.cashierId())
                    .param("key", req.idempotencyKey()).param("attested", new java.math.BigDecimal(req.attestedAmount()))
                    .param("snapshot", quote.total()).param("method", method.name()).param("reason", reason)
                    .param("by", adminId).param("at", time.now())
                    .update();
            audit.record(adminId, "RECOVERY_AMOUNT_MISMATCH", "order_quote", quoteId, Map.of(
                    "exceptionId", exceptionId, "attestedAmount", req.attestedAmount(), "snapshotTotal", snapshotTotal));
            return new Mismatch(exceptionId, snapshotTotal, req.attestedAmount());
        }

        OpenShift shift = writer.requireOpenShift();
        UUID orderId = writer.create(quote, quote.cashierId(), adminId, shift, "EXCEPTION", method, reference, adminId);
        if (req.idempotencyKey() != null) {
            // stored on the original cashier, so the cashier's retry with that key returns this order with 200
            writer.insertKey(quote.cashierId(), req.idempotencyKey(),
                    OrderWriter.requestHash(quoteId, method, reference), OrderService.SOURCE_RECOVERY, orderId);
        }
        jdbc.sql("""
                        update external_action_exception set resolved_order_id = :order
                        where quote_id = :quote and state = 'PENDING' and resolved_order_id is null""")
                .param("order", orderId).param("quote", quoteId).update();
        Map<String, Object> meta = new HashMap<>();
        meta.put("quoteId", quoteId);
        meta.put("paymentMethod", method.name());
        meta.put("cashierKeySupplied", req.idempotencyKey() != null);
        audit.record(adminId, "QUOTE_RECOVERED", "orders", orderId, meta);
        events.publishEvent(new TicketChanged(orderId, 0, "CREATED"));
        return new Recovered(reads.full(orderId).orElseThrow(), true);
    }

    /** Closes a pending exception without creating a BrewFlow order. */
    @Transactional
    public void resolveOutside(UUID adminId, UUID exceptionId) {
        int updated = jdbc.sql("""
                        update external_action_exception set state = 'RESOLVED_OUTSIDE', resolved_by = :by, resolved_at = :at
                        where id = :id and state = 'PENDING' and resolved_order_id is null""")
                .param("id", exceptionId).param("by", adminId).param("at", time.now()).update();
        if (updated == 0) {
            boolean exists = jdbc.sql("select exists (select 1 from external_action_exception where id = :id)")
                    .param("id", exceptionId).query(Boolean.class).single();
            if (!exists) {
                throw ApiException.notFound();
            }
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "This exception is already resolved.");
        }
        audit.record(adminId, "EXCEPTION_RESOLVED_OUTSIDE", "external_action_exception", exceptionId, Map.of());
    }
}
