package app.brewflow.kds;

import app.brewflow.audit.AuditService;
import app.brewflow.common.ApiException;
import app.brewflow.common.ErrorCode;
import app.brewflow.kds.TicketStreamHub.TicketChanged;
import app.brewflow.order.OrderReadService;
import app.brewflow.order.OrderViews.Kitchen;
import app.brewflow.security.StaffPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Section 4.5 kitchen transitions. prep_version changes only on a successful prep transition. */
@Service
public class PrepService {

    public enum PrepStatus {
        NEW, PREPARING, READY, CLEARED, CANCELLED;

        /** Edges reachable through PATCH /orders/{id}/prep-status. CANCELLED is only reached by a confirmed void. */
        boolean canMoveTo(PrepStatus target) {
            return switch (this) {
                case NEW -> target == PREPARING;
                case PREPARING -> target == READY;
                case READY -> target == CLEARED;
                case CLEARED, CANCELLED -> false;
            };
        }

        public boolean isOpen() {
            return this == NEW || this == PREPARING || this == READY;
        }
    }

    private record Current(PrepStatus status, int version) {
    }

    private final JdbcClient jdbc;
    private final OrderReadService reads;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public PrepService(JdbcClient jdbc, OrderReadService reads, AuditService audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.reads = reads;
        this.audit = audit;
        this.events = events;
    }

    @Transactional
    public Kitchen transition(StaffPrincipal actor, UUID orderId, String rawTarget, Integer expectedVersion) {
        PrepStatus target = parse(rawTarget);
        if (expectedVersion == null) {
            throw ApiException.invalid("expectedVersion", "expectedVersion is required.");
        }
        if (target == PrepStatus.CANCELLED) {
            if (!actor.isAdmin()) {
                throw new ApiException(ErrorCode.FORBIDDEN, "Only an admin can cancel, through a confirmed void.");
            }
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "CANCELLED is set only by a confirmed VOID_NOTE.",
                    Map.of("use", "POST /admin/orders/{id}/corrections"));
        }
        Current current = jdbc.sql("select prep_status, prep_version from orders where id = :id for update")
                .param("id", orderId)
                .query((rs, n) -> new Current(PrepStatus.valueOf(rs.getString(1)), rs.getInt(2)))
                .optional().orElseThrow(ApiException::notFound);

        // a repeated PATCH with the same target and the already-applied version returns the order unchanged
        if (current.status() == target && expectedVersion == current.version() - 1) {
            return reads.kitchen(orderId).orElseThrow();
        }
        if (expectedVersion != current.version()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "The ticket changed. Reload the board.",
                    Map.of("currentVersion", current.version(), "currentStatus", current.status().name()));
        }
        if (!current.status().canMoveTo(target)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION,
                    current.status() + " cannot move to " + target + ".",
                    Map.of("from", current.status().name(), "to", target.name()));
        }
        int version = jdbc.sql("""
                        update orders set prep_status = :status, prep_version = prep_version + 1
                        where id = :id returning prep_version""")
                .param("id", orderId).param("status", target.name()).query(Integer.class).single();
        audit.record(actor.id(), "PREP_STATUS_CHANGED", "orders", orderId,
                Map.of("from", current.status().name(), "to", target.name(), "version", version));
        events.publishEvent(new TicketChanged(orderId, version, "PREP_STATUS"));
        return reads.kitchen(orderId).orElseThrow();
    }

    private static PrepStatus parse(String raw) {
        if (raw != null) {
            for (PrepStatus s : PrepStatus.values()) {
                if (s.name().equals(raw)) {
                    return s;
                }
            }
        }
        throw ApiException.invalid("prepStatus", "prepStatus must be PREPARING, READY or CLEARED.");
    }
}
