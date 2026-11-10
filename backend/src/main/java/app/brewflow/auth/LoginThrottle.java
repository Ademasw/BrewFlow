package app.brewflow.auth;

import app.brewflow.common.BrewFlowProperties;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-address throttle on failed logins (section 8). Per-account lockout lives in staff_user.failed_attempts.
 */
@Component
public class LoginThrottle {

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final long windowMillis;
    private final Clock clock;

    public LoginThrottle(BrewFlowProperties props, Clock clock) {
        this.maxFailures = props.login().ipMaxFailures();
        this.windowMillis = props.login().ipWindow().toMillis();
        this.clock = clock;
    }

    public boolean isBlocked(String address) {
        Deque<Long> times = failures.get(address);
        if (times == null) {
            return false;
        }
        synchronized (times) {
            prune(times);
            return times.size() >= maxFailures;
        }
    }

    public void recordFailure(String address) {
        Deque<Long> times = failures.computeIfAbsent(address, a -> new ArrayDeque<>());
        synchronized (times) {
            prune(times);
            times.addLast(clock.millis());
        }
    }

    public void reset() {
        failures.clear();
    }

    private void prune(Deque<Long> times) {
        long cutoff = clock.millis() - windowMillis;
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.removeFirst();
        }
    }
}
