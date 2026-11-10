package app.brewflow.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.brewflow.audit.AuditService.AuditEvent;
import app.brewflow.common.ApiException;
import app.brewflow.common.Json;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin: audit", description = "Append-only, redacted audit trail")
@RequestMapping("/api/v1/admin/audit")
public class AuditController {

    private final AuditService audit;
    private final Json json;

    public AuditController(AuditService audit, Json json) {
        this.audit = audit;
        this.json = json;
    }

    @Operation(summary = "Audit events, newest first; page with before and limit")
    @GetMapping
    Map<String, Object> page(@RequestParam(required = false) OffsetDateTime before,
                             @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) {
            throw ApiException.invalid("limit", "limit must be between 1 and 200.");
        }
        List<AuditEvent> rows = audit.page(before, limit);
        List<Map<String, Object>> items = rows.stream().map(this::view).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextBefore", rows.size() == limit ? rows.getLast().createdAt() : null);
        return body;
    }

    private Map<String, Object> view(AuditEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("actorId", e.actorId());
        m.put("eventType", e.eventType());
        m.put("entityType", e.entityType());
        m.put("entityId", e.entityId());
        m.put("requestId", e.requestId());
        m.put("metadata", json.read(e.metadata(), Map.class));
        m.put("createdAt", e.createdAt());
        return m;
    }
}
