package app.brewflow.kds;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.order.OrderReadService;
import app.brewflow.order.OrderViews.Kitchen;
import app.brewflow.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Tag(name = "Kitchen (KDS)", description = "Open tickets, prep transitions and the SSE hint stream")
public class KdsController {

    public record OpenTickets(OffsetDateTime snapshotAt, List<Kitchen> tickets) {
    }

    public record PrepStatusRequest(String prepStatus, Integer expectedVersion) {
    }

    private final OrderReadService reads;
    private final PrepService prep;
    private final TicketStreamHub hub;
    private final TimeSource time;

    public KdsController(OrderReadService reads, PrepService prep, TicketStreamHub hub, TimeSource time) {
        this.reads = reads;
        this.prep = prep;
        this.hub = hub;
        this.time = time;
    }

    /** Database snapshot of NEW, PREPARING and READY. The board reloads it on every event, focus and reconnect. */
    @Operation(summary = "Snapshot of NEW, PREPARING and READY tickets")
    @GetMapping("/api/v1/kds/open-tickets")
    OpenTickets openTickets() {
        OffsetDateTime at = time.now();
        return new OpenTickets(at, reads.openTickets());
    }

    @Operation(summary = "SSE stream: event ticket.changed after commit, heartbeat ~25 s")
    @GetMapping(path = "/api/v1/kds/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return hub.subscribe(CurrentUser.get().id(), request.getSession().getId());
    }

    @Operation(summary = "Prep transition with expectedVersion (section 4.5); never CANCELLED")
    @PatchMapping("/api/v1/orders/{id}/prep-status")
    Kitchen prepStatus(@PathVariable UUID id, @RequestBody PrepStatusRequest body) {
        return prep.transition(CurrentUser.get(), id, body.prepStatus(), body.expectedVersion());
    }
}
