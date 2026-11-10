package app.brewflow.auth;

import app.brewflow.audit.AuditService;
import app.brewflow.common.ApiException;
import app.brewflow.common.BrewFlowProperties;
import app.brewflow.kds.TicketStreamHub.SessionsRevoked;
import app.brewflow.staff.CredentialPolicy;
import app.brewflow.staff.StaffRepository;
import app.brewflow.staff.StaffUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    public enum Status { OK, INVALID, LOCKED }

    public record LoginOutcome(Status status, StaffUser user) {
    }

    private final StaffRepository staff;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final int maxFailedAttempts;
    // compared against when the login is unknown, so response time does not reveal which logins exist
    private final String dummyHash;

    public AuthService(StaffRepository staff, PasswordEncoder encoder, AuditService audit,
                       ApplicationEventPublisher events, BrewFlowProperties props) {
        this.staff = staff;
        this.encoder = encoder;
        this.audit = audit;
        this.events = events;
        this.maxFailedAttempts = props.login().maxFailedAttempts();
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public LoginOutcome attemptLogin(String login, String credential) {
        String normalized = StaffUser.normalizeLogin(login);
        var found = staff.findByLoginForUpdate(normalized);
        if (found.isEmpty()) {
            encoder.matches(credential, dummyHash);
            audit.record(null, "LOGIN_FAILED", "staff_user", null, Map.of("reason", "unknown_login"));
            return new LoginOutcome(Status.INVALID, null);
        }
        StaffUser user = found.get();
        if (user.locked()) {
            audit.record(user.id(), "LOGIN_REJECTED_LOCKED", "staff_user", user.id(), Map.of());
            return new LoginOutcome(Status.LOCKED, null);
        }
        boolean matches = encoder.matches(credential, user.credentialHash());
        if (!matches || !user.active()) {
            if (user.active()) {
                int failed = staff.recordFailedAttempt(user.id());
                if (failed >= maxFailedAttempts) {
                    staff.lock(user.id());
                    audit.record(user.id(), "ACCOUNT_LOCKED", "staff_user", user.id(), Map.of("failedAttempts", failed));
                    return new LoginOutcome(Status.LOCKED, null);
                }
            }
            audit.record(user.id(), "LOGIN_FAILED", "staff_user", user.id(),
                    Map.of("reason", user.active() ? "bad_credential" : "inactive"));
            return new LoginOutcome(Status.INVALID, null);
        }
        staff.clearFailedAttempts(user.id());
        audit.record(user.id(), "LOGIN_SUCCEEDED", "staff_user", user.id(), Map.of());
        return new LoginOutcome(Status.OK, user);
    }

    /** Changes the caller's own credential. Returns the new session epoch; every other session dies. */
    @Transactional
    public int changeOwnCredential(UUID userId, String current, String next) {
        StaffUser user = staff.findByIdForUpdate(userId).orElseThrow(ApiException::notFound);
        if (current == null || !encoder.matches(current, user.credentialHash())) {
            throw ApiException.invalid("currentCredential", "The current credential is wrong.");
        }
        CredentialPolicy.validate(user.role(), "newCredential", next);
        if (encoder.matches(next, user.credentialHash())) {
            throw ApiException.invalid("newCredential", "The new credential must differ from the current one.");
        }
        int epoch = staff.changeOwnCredential(userId, encoder.encode(next));
        audit.record(userId, "CREDENTIAL_CHANGED", "staff_user", userId, Map.of());
        events.publishEvent(new SessionsRevoked(userId));
        return epoch;
    }

    @Transactional
    public void recordLogout(UUID userId) {
        audit.record(userId, "LOGOUT", "staff_user", userId, Map.of());
    }
}
