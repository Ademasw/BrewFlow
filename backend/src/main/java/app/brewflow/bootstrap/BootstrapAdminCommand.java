package app.brewflow.bootstrap;

import app.brewflow.audit.AuditService;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.staff.CredentialPolicy;
import app.brewflow.staff.Role;
import app.brewflow.staff.StaffRepository;
import app.brewflow.staff.StaffUser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Section 2.1: {@code bootstrap-admin} creates the one first ADMIN and exits. No HTTP route can do this.
 * It writes nothing and exits non-zero when an ADMIN row exists, the password is shorter than 12 characters,
 * or the password file is readable by other operating-system users. The web process never calls this.
 */
@Component
public class BootstrapAdminCommand {

    public static final String ARGUMENT = "bootstrap-admin";
    public static final String ENV_LOGIN = "BOOTSTRAP_ADMIN_LOGIN";
    public static final String ENV_PASSWORD_FILE = "BOOTSTRAP_ADMIN_PASSWORD_FILE";

    public static final int OK = 0;
    public static final int BAD_CONFIGURATION = 2;
    public static final int FILE_READABLE_BY_OTHERS = 3;
    public static final int PASSWORD_TOO_SHORT = 4;
    public static final int ADMIN_EXISTS = 5;

    private static final long BOOTSTRAP_LOCK = 0x42726577466C6F77L;

    private final StaffRepository staff;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final TimeSource time;

    public BootstrapAdminCommand(StaffRepository staff, PasswordEncoder encoder, AuditService audit,
                                 TransactionTemplate tx, JdbcClient jdbc, TimeSource time) {
        this.staff = staff;
        this.encoder = encoder;
        this.audit = audit;
        this.tx = tx;
        this.jdbc = jdbc;
        this.time = time;
    }

    public int run(Map<String, String> env, PrintStream err) {
        String login = env.get(ENV_LOGIN);
        String file = env.get(ENV_PASSWORD_FILE);
        if (login == null || login.isBlank() || file == null || file.isBlank()) {
            err.println("bootstrap-admin: " + ENV_LOGIN + " and " + ENV_PASSWORD_FILE + " are required");
            return BAD_CONFIGURATION;
        }
        String normalized = StaffUser.normalizeLogin(login);
        if (!normalized.matches("[a-z0-9._-]{1,64}")) {
            err.println("bootstrap-admin: login uses 1 to 64 letters, digits, dot, dash or underscore");
            return BAD_CONFIGURATION;
        }
        Path path = Path.of(file);
        String password;
        try {
            if (!Files.isRegularFile(path)) {
                err.println("bootstrap-admin: password file not found");
                return BAD_CONFIGURATION;
            }
            if (PasswordFileCheck.readableByOthers(path)) {
                err.println("bootstrap-admin: password file is readable by other users; restrict it to the owner");
                return FILE_READABLE_BY_OTHERS;
            }
            password = stripLineEnd(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            err.println("bootstrap-admin: cannot read password file");
            return BAD_CONFIGURATION;
        }
        if (password.codePointCount(0, password.length()) < CredentialPolicy.ADMIN_MIN_LENGTH) {
            err.println("bootstrap-admin: password must have at least 12 characters");
            return PASSWORD_TOO_SHORT;
        }
        String hash = encoder.encode(password);
        Integer result = tx.execute(status -> {
            jdbc.sql("select pg_advisory_xact_lock(:k)").param("k", BOOTSTRAP_LOCK).query((rs, n) -> 1).single();
            if (staff.adminExists()) {
                return ADMIN_EXISTS;
            }
            UUID id = UUID.randomUUID();
            staff.insert(id, login, login.trim(), Role.ADMIN, hash, time.now());
            audit.record(id, "ADMIN_BOOTSTRAPPED", "staff_user", id, Map.of("login", normalized));
            return OK;
        });
        if (result == ADMIN_EXISTS) {
            err.println("bootstrap-admin: an ADMIN account already exists; nothing was written");
        } else {
            err.println("bootstrap-admin: admin '" + normalized + "' created; delete the password file now");
        }
        return result;
    }

    private static String stripLineEnd(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }
}
