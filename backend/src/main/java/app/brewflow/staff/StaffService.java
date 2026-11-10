package app.brewflow.staff;

import app.brewflow.audit.AuditService;
import app.brewflow.common.ApiException;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.kds.TicketStreamHub.SessionsRevoked;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Section 2.1: staff administration by an admin session. */
@Service
public class StaffService {

    public record CreateStaff(String login, String displayName, Role role, String temporaryCredential) {
    }

    public record UpdateStaff(String displayName, Role role, Boolean active, int expectedRevision) {
    }

    private final StaffRepository staff;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TimeSource time;

    public StaffService(StaffRepository staff, PasswordEncoder encoder, AuditService audit,
                        ApplicationEventPublisher events, TimeSource time) {
        this.staff = staff;
        this.encoder = encoder;
        this.audit = audit;
        this.events = events;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public List<StaffUser> list() {
        return staff.findAll();
    }

    @Transactional
    public StaffUser create(UUID actorId, CreateStaff req) {
        String normalized = StaffUser.normalizeLogin(req.login());
        if (normalized.isEmpty() || normalized.length() > 64 || !normalized.matches("[a-z0-9._-]+")) {
            throw ApiException.invalid("login", "Login uses 1 to 64 letters, digits, dot, dash or underscore.");
        }
        CredentialPolicy.validate(req.role(), "temporaryCredential", req.temporaryCredential());
        if (staff.loginExists(normalized)) {
            throw ApiException.invalid("login", "This login is already taken.");
        }
        UUID id = UUID.randomUUID();
        staff.insert(id, req.login(), req.displayName(), req.role(), encoder.encode(req.temporaryCredential()), time.now());
        audit.record(actorId, "STAFF_CREATED", "staff_user", id, Map.of("login", normalized, "role", req.role().name()));
        return staff.findById(id).orElseThrow();
    }

    @Transactional
    public StaffUser update(UUID actorId, UUID id, UpdateStaff req) {
        boolean mayRemoveAdmin = Boolean.FALSE.equals(req.active()) || (req.role() != null && req.role() != Role.ADMIN);
        int activeAdmins = mayRemoveAdmin ? staff.lockActiveAdmins() : -1;
        StaffUser current = staff.findByIdForUpdate(id).orElseThrow(ApiException::notFound);
        if (current.revision() != req.expectedRevision()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "This staff record changed. Reload and try again.",
                    Map.of("currentRevision", current.revision()));
        }
        String displayName = req.displayName() == null ? current.displayName() : req.displayName().trim();
        Role role = req.role() == null ? current.role() : req.role();
        boolean active = req.active() == null ? current.active() : req.active();
        boolean losesAdmin = current.active() && current.role() == Role.ADMIN && (!active || role != Role.ADMIN);
        if (losesAdmin && activeAdmins <= 1) {
            throw new ApiException(ErrorCode.LAST_ACTIVE_ADMIN, "The last active admin cannot be disabled or demoted.");
        }
        boolean endSessions = (current.active() && !active) || role != current.role();
        staff.update(id, displayName, role, active, endSessions);
        audit.record(actorId, "STAFF_UPDATED", "staff_user", id, Map.of(
                "role", role.name(), "active", active, "sessionsEnded", endSessions));
        if (endSessions) {
            events.publishEvent(new SessionsRevoked(id));
        }
        return staff.findById(id).orElseThrow();
    }

    /** The only unlock path: temporary credential, counters cleared, forced change, sessions ended. */
    @Transactional
    public void resetCredential(UUID actorId, UUID id, String temporaryCredential) {
        StaffUser current = staff.findByIdForUpdate(id).orElseThrow(ApiException::notFound);
        CredentialPolicy.validate(current.role(), "temporaryCredential", temporaryCredential);
        staff.resetCredential(id, encoder.encode(temporaryCredential));
        audit.record(actorId, "STAFF_CREDENTIAL_RESET", "staff_user", id, Map.of("unlocked", current.locked()));
        events.publishEvent(new SessionsRevoked(id));
    }
}
