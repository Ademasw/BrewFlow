package app.brewflow.audit;

import app.brewflow.common.Json;
import app.brewflow.common.RequestIdFilter;
import app.brewflow.common.TimeConfig.TimeSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Append-only audit trail. Callers pass redacted metadata only: never a PIN, password, cookie or token. */
@Service
public class AuditService {

    public record AuditEvent(UUID id, UUID actorId, String eventType, String entityType, UUID entityId,
                             String requestId, String metadata, OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final TimeSource time;

    public AuditService(JdbcClient jdbc, Json json, TimeSource time) {
        this.jdbc = jdbc;
        this.json = json;
        this.time = time;
    }

    public void record(UUID actorId, String eventType, String entityType, UUID entityId, Map<String, ?> metadata) {
        jdbc.sql("""
                        insert into audit_event (id, actor_id, event_type, entity_type, entity_id, request_id, metadata, created_at)
                        values (:id, :actor, :type, :entityType, :entityId, :requestId, cast(:metadata as jsonb), :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("actor", actorId)
                .param("type", eventType)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("requestId", MDC.get(RequestIdFilter.MDC_REQUEST_ID))
                .param("metadata", json.write(metadata == null ? Map.of() : metadata))
                .param("at", time.now())
                .update();
    }

    public List<AuditEvent> page(OffsetDateTime before, int limit) {
        return jdbc.sql("""
                        select id, actor_id, event_type, entity_type, entity_id, request_id, metadata::text as metadata, created_at
                        from audit_event
                        where (cast(:before as timestamptz) is null or created_at < :before)
                        order by created_at desc, id desc
                        limit :limit
                        """)
                .param("before", before)
                .param("limit", limit)
                .query((rs, n) -> new AuditEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class),
                        rs.getString("request_id"),
                        rs.getString("metadata"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }
}
