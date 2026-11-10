package app.brewflow.kds;

import app.brewflow.common.Json;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Section 7: SSE hints sent only after commit. No replay; clients reload the snapshot on every event,
 * focus and reconnect.
 */
@Component
public class TicketStreamHub {

    /** Published inside a transaction; delivered to KDS clients only if it commits. */
    public record TicketChanged(UUID orderId, int version, String type) {
    }

    /** Published inside a transaction that ended an account's sessions. */
    public record SessionsRevoked(UUID userId) {
    }

    private record Subscriber(SseEmitter emitter, UUID userId, String sessionId) {
    }

    private static final Logger log = LoggerFactory.getLogger(TicketStreamHub.class);
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
    private final Json json;

    public TicketStreamHub(Json json) {
        this.json = json;
    }

    public SseEmitter subscribe(UUID userId, String sessionId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscriber subscriber = new Subscriber(emitter, userId, sessionId);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> {
            subscribers.remove(subscriber);
            emitter.complete();
        });
        emitter.onError(e -> subscribers.remove(subscriber));
        send(subscriber, SseEmitter.event().comment("connected"));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTicketChanged(TicketChanged event) {
        String data = json.write(Map.of("orderId", event.orderId(), "version", event.version(), "type", event.type()));
        for (Subscriber s : subscribers) {
            send(s, SseEmitter.event().name("ticket.changed").data(data));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionsRevoked(SessionsRevoked event) {
        closeForUser(event.userId());
    }

    @Scheduled(fixedDelayString = "${brewflow.kds.heartbeat}", initialDelayString = "${brewflow.kds.heartbeat}")
    public void heartbeat() {
        for (Subscriber s : subscribers) {
            send(s, SseEmitter.event().comment("heartbeat"));
        }
    }

    public void closeForUser(UUID userId) {
        subscribers.stream().filter(s -> s.userId().equals(userId)).forEach(this::close);
    }

    public void closeForSession(String sessionId) {
        subscribers.stream().filter(s -> s.sessionId().equals(sessionId)).forEach(this::close);
    }

    public int activeCount() {
        return subscribers.size();
    }

    private void close(Subscriber s) {
        subscribers.remove(s);
        try {
            s.emitter().complete();
        } catch (RuntimeException e) {
            log.debug("SSE emitter already closed");
        }
    }

    private void send(Subscriber s, SseEmitter.SseEventBuilder event) {
        try {
            s.emitter().send(event);
        } catch (Exception e) {
            // dead client: drop it so no emitter is left behind
            subscribers.remove(s);
            try {
                s.emitter().completeWithError(e);
            } catch (RuntimeException ignored) {
                // already completed
            }
        }
    }
}
