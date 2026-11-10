package app.brewflow.correction;

import app.brewflow.audit.AuditService;
import app.brewflow.common.ApiException;
import app.brewflow.common.AppMode;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.kds.PrepService.PrepStatus;
import app.brewflow.kds.TicketStreamHub.TicketChanged;
import app.brewflow.order.OrderReadService;
import app.brewflow.order.OrderViews.Order;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Section 4.6: pilot corrections are full-order VOID_NOTE only, append-only. CANCELLED has one write path:
 * a CONFIRMED VOID_NOTE (ADR-015). A pending void is confirmed by a new note that references it.
 */
@Service
public class CorrectionService {

    public record VoidRequest(String type, String externalActionState, UUID confirmsCorrectionId, String reason) {
    }

    private final JdbcClient jdbc;
    private final OrderReadService reads;
    private final AppMode mode;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TimeSource time;

    public CorrectionService(JdbcClient jdbc, OrderReadService reads, AppMode mode, AuditService audit,
                             ApplicationEventPublisher events, TimeSource time) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.mode = mode;
        this.audit = audit;
        this.events = events;
        this.time = time;
    }

    @Transactional
    public Order append(UUID actorId, UUID orderId, VoidRequest req) {
        if (!"VOID_NOTE".equals(req.type())) {
            throw ApiException.invalid("type", "Pilot corrections are full-order VOID_NOTE only.");
        }
        String state = req.externalActionState();
        if (!"PENDING".equals(state) && !"CONFIRMED".equals(state) && !"NOT_APPLICABLE".equals(state)) {
            throw ApiException.invalid("externalActionState", "externalActionState must be PENDING, CONFIRMED or NOT_APPLICABLE.");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty() || reason.length() > 500) {
            throw ApiException.invalid("reason", "reason needs 1 to 500 characters.");
        }
        if (req.confirmsCorrectionId() != null && !"CONFIRMED".equals(state)) {
            throw ApiException.invalid("confirmsCorrectionId", "Only a CONFIRMED note can confirm a pending note.");
        }

        PrepStatus prep = jdbc.sql("select prep_status from orders where id = :id for update")
                .param("id", orderId).query((rs, n) -> PrepStatus.valueOf(rs.getString(1)))
                .optional().orElseThrow(ApiException::notFound);

        if ("NOT_APPLICABLE".equals(state) && mode.isLive()) {
            throw new ApiException(ErrorCode.MODE_REJECTED, "NOT_APPLICABLE is allowed only in TRAINING.");
        }
        boolean confirmed = jdbc.sql("""
                        select exists (select 1 from order_correction where order_id = :id and external_action_state = 'CONFIRMED')""")
                .param("id", orderId).query(Boolean.class).single();
        Optional<UUID> openPending = jdbc.sql("""
                        select c.id from order_correction c
                        where c.order_id = :id and c.external_action_state = 'PENDING'
                          and not exists (select 1 from order_correction k where k.confirms_correction_id = c.id)""")
                .param("id", orderId).query((rs, n) -> rs.getObject(1, UUID.class)).optional();

        UUID confirms = null;
        switch (state) {
            case "PENDING" -> {
                if (confirmed || openPending.isPresent()) {
                    throw new ApiException(ErrorCode.ALREADY_VOIDED, "This order already has a void note.");
                }
            }
            case "CONFIRMED" -> {
                if (confirmed) {
                    throw new ApiException(ErrorCode.ALREADY_VOIDED, "This order is already voided.");
                }
                if (req.confirmsCorrectionId() != null) {
                    if (openPending.isEmpty() || !openPending.get().equals(req.confirmsCorrectionId())) {
                        throw ApiException.invalid("confirmsCorrectionId",
                                "confirmsCorrectionId must name the open PENDING note of this order.");
                    }
                }
                // a direct CONFIRMED note on an order with an open pending note confirms that note,
                // so the pending count still falls
                confirms = openPending.orElse(null);
            }
            default -> {
                // NOT_APPLICABLE: annotation only
            }
        }

        UUID noteId = UUID.randomUUID();
        jdbc.sql("""
                        insert into order_correction (id, order_id, type, external_action_state, confirms_correction_id,
                                                      reason, created_by, created_at)
                        values (:id, :order, 'VOID_NOTE', :state, :confirms, :reason, :by, :at)""")
                .param("id", noteId).param("order", orderId).param("state", state).param("confirms", confirms)
                .param("reason", reason).param("by", actorId).param("at", time.now())
                .update();

        Map<String, Object> meta = new HashMap<>();
        meta.put("externalActionState", state);
        if (confirms != null) {
            meta.put("confirmsCorrectionId", confirms);
        }
        if ("CONFIRMED".equals(state) && prep.isOpen()) {
            int version = jdbc.sql("""
                            update orders set prep_status = 'CANCELLED', prep_version = prep_version + 1
                            where id = :id returning prep_version""")
                    .param("id", orderId).query(Integer.class).single();
            meta.put("cancelledFrom", prep.name());
            events.publishEvent(new TicketChanged(orderId, version, "CANCELLED"));
        }
        audit.record(actorId, "VOID_NOTE_APPENDED", "orders", orderId, meta);
        return reads.full(orderId).orElseThrow();
    }
}
