package app.brewflow.support;

import app.brewflow.auth.LoginThrottle;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.staff.Role;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

/**
 * Real HTTP against a running server and a real PostgreSQL 18 database (section 1: real PostgreSQL for every
 * transaction and concurrency test). No Docker on the build machine, so the database is the local cluster from
 * scripts/dev-db.sh instead of a Testcontainers postgres:18 container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${BREWFLOW_TEST_DB_URL:jdbc:postgresql://127.0.0.1:55432/brewflow_test}",
        "server.servlet.session.cookie.secure=false",
        "brewflow.app-mode=TRAINING",
        "brewflow.deployment-tier=test",
        "logging.level.brewflow.access=WARN"
})
@Import(TestClockConfig.class)
public abstract class IntegrationTest {

    public static final String ADMIN_PASSWORD = "admin-pass-0001";
    public static final String CASHIER_PIN = "482913";
    public static final String CASHIER2_PIN = "551177";
    public static final String BARISTA_PIN = "730264";

    @Autowired protected JdbcClient jdbc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected MutableClock clock;
    @Autowired protected TimeSource time;
    @Autowired protected LoginThrottle throttle;
    @Autowired private Environment env;

    protected UUID adminId;
    protected UUID cashierId;
    protected UUID cashier2Id;
    protected UUID baristaId;

    @BeforeEach
    void resetDatabase() {
        jdbc.sql("""
                truncate table audit_event, order_correction, external_action_exception, idempotency_record,
                    payment_record, order_line_modifier, order_line, orders, human_no_counter, order_quote, shift,
                    menu_item_modifier_group, modifier_option, modifier_group, menu_item, category, staff_user cascade
                """).update();
        jdbc.sql("update menu_catalog set revision = 0").update();
        clock.reset();
        throttle.reset();
        adminId = insertStaff("admin", Role.ADMIN, ADMIN_PASSWORD, false);
        cashierId = insertStaff("cashier1", Role.CASHIER, CASHIER_PIN, false);
        cashier2Id = insertStaff("cashier2", Role.CASHIER, CASHIER2_PIN, false);
        baristaId = insertStaff("barista1", Role.BARISTA, BARISTA_PIN, false);
    }

    protected int port() {
        return env.getRequiredProperty("local.server.port", Integer.class);
    }

    protected ApiClient client() {
        return new ApiClient(port(), mapper);
    }

    protected ApiClient admin() {
        return loggedIn("admin", ADMIN_PASSWORD);
    }

    protected ApiClient cashier() {
        return loggedIn("cashier1", CASHIER_PIN);
    }

    protected ApiClient cashier2() {
        return loggedIn("cashier2", CASHIER2_PIN);
    }

    protected ApiClient barista() {
        return loggedIn("barista1", BARISTA_PIN);
    }

    protected ApiClient loggedIn(String login, String credential) {
        ApiClient c = client();
        ApiClient.Resp r = c.login(login, credential);
        if (r.status() != 200) {
            throw new IllegalStateException("login failed for " + login + ": " + r.status() + " " + r.raw());
        }
        return c;
    }

    /** Test seed only; production accounts come from bootstrap-admin and /admin/staff. */
    protected UUID insertStaff(String login, Role role, String credential, boolean mustChange) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into staff_user (id, login, login_normalized, display_name, role, credential_hash,
                                                must_change_credential)
                        values (:id, :login, :login, :name, :role, :hash, :must)""")
                .param("id", id).param("login", login).param("name", "Test " + login).param("role", role.name())
                .param("hash", encoder.encode(credential)).param("must", mustChange)
                .update();
        return id;
    }

    protected long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    protected long count(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    protected UUID openShift(ApiClient admin) {
        ApiClient.Resp r = admin.post("/api/v1/admin/shifts/open", null);
        if (r.status() != 201) {
            throw new IllegalStateException("shift open failed: " + r.raw());
        }
        return UUID.fromString(r.text("id"));
    }
}
