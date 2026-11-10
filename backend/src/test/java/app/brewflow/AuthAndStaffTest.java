package app.brewflow;

import app.brewflow.staff.Role;
import app.brewflow.support.ApiClient;
import app.brewflow.support.ApiClient.Resp;
import app.brewflow.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** Sprint 1 (BF-S1-03, BF-S1-06) and T042: sessions, CSRF, roles, staff lifecycle, scrubbed logs. */
@ExtendWith(OutputCaptureExtension.class)
class AuthAndStaffTest extends IntegrationTest {

    @Test
    void mutationWithoutCsrfIsRefused() {
        ApiClient c = client();
        c.refreshCsrf();
        Resp r = c.postWithoutCsrf("/api/v1/auth/login", Map.of("login", "admin", "credential", ADMIN_PASSWORD));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("CSRF_INVALID");

        ApiClient admin = admin();
        Resp noCsrf = admin.postWithoutCsrf("/api/v1/admin/shifts/open", null);
        assertThat(noCsrf.status()).isEqualTo(403);
        assertThat(noCsrf.code()).isEqualTo("CSRF_INVALID");
        assertThat(count("select count(*) from shift")).isZero();
    }

    @Test
    void errorEnvelopeCarriesRequestId() {
        Resp r = client().get("/api/v1/auth/me");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHENTICATED");
        assertThat(r.body().path("error").path("requestId").asString()).isNotBlank();
        assertThat(r.http().headers().firstValue("X-Request-Id")).isPresent();
        assertThat(r.body().path("error").has("details")).isTrue();
    }

    @Test
    void healthIsPublicAndUp() {
        Resp r = client().get("/actuator/health/readiness");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("UP");
    }

    @Test
    void wrongCredentialIs401AndMeShowsIdentity() {
        assertThat(client().login("admin", "wrong-password-123").status()).isEqualTo(401);
        assertThat(client().login("nobody", "whatever-123456").status()).isEqualTo(401);

        ApiClient admin = admin();
        Resp me = admin.get("/api/v1/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.text("role")).isEqualTo("ADMIN");
        assertThat(me.text("appMode")).isEqualTo("TRAINING");
        assertThat(me.body().path("mustChangeCredential").asBoolean()).isFalse();
    }

    @Test
    void roleMatrixIsEnforcedOnTheBackend() {
        ApiClient anonymous = client();
        assertThat(anonymous.get("/api/v1/menu").status()).isEqualTo(401);
        assertThat(anonymous.get("/api/v1/kds/open-tickets").status()).isEqualTo(401);

        ApiClient barista = barista();
        assertThat(barista.get("/api/v1/menu").status()).isEqualTo(403);
        assertThat(barista.post("/api/v1/orders/quotes", Map.of("lines", java.util.List.of())).code()).isEqualTo("FORBIDDEN");
        assertThat(barista.get("/api/v1/admin/staff").status()).isEqualTo(403);
        assertThat(barista.get("/api/v1/kds/open-tickets").status()).isEqualTo(200);

        ApiClient cashier = cashier();
        assertThat(cashier.get("/api/v1/menu").status()).isEqualTo(200);
        assertThat(cashier.get("/api/v1/kds/open-tickets").status()).isEqualTo(403);
        assertThat(cashier.get("/api/v1/admin/staff").status()).isEqualTo(403);
        assertThat(cashier.get("/api/v1/admin/audit").status()).isEqualTo(403);
        assertThat(cashier.post("/api/v1/admin/menu/categories", Map.of("title", "X")).status()).isEqualTo(403);

        ApiClient admin = admin();
        assertThat(admin.get("/api/v1/admin/staff").status()).isEqualTo(200);
        assertThat(admin.get("/api/v1/kds/open-tickets").status()).isEqualTo(200);
    }

    @Test
    void loginRotatesSessionAndLogoutKillsIt() {
        ApiClient c = client();
        c.refreshCsrf();
        String anonymousSession = c.sessionCookie().orElseThrow();
        assertThat(c.login("cashier1", CASHIER_PIN).status()).isEqualTo(200);
        String loggedInSession = c.sessionCookie().orElseThrow();
        assertThat(loggedInSession).isNotEqualTo(anonymousSession);

        assertThat(c.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
        assertThat(c.get("/api/v1/auth/me").status()).isEqualTo(401);

        ApiClient replay = client();
        replay.useSessionCookie(loggedInSession);
        assertThat(replay.get("/api/v1/auth/me").status()).isEqualTo(401);
    }

    @Test
    void staffSwitchKillsTheOldSession() {
        ApiClient tablet = cashier();
        String cashierSession = tablet.sessionCookie().orElseThrow();
        assertThat(tablet.login("cashier2", CASHIER2_PIN).status()).isEqualTo(200);
        assertThat(tablet.get("/api/v1/auth/me").text("login")).isEqualTo("cashier2");

        ApiClient replay = client();
        replay.useSessionCookie(cashierSession);
        assertThat(replay.get("/api/v1/auth/me").status()).isEqualTo(401);
    }

    @Test
    void newStaffMustChangeCredentialBeforeAnythingElse() {
        ApiClient admin = admin();
        Resp created = admin.post("/api/v1/admin/staff", Map.of("login", "Newbie", "displayName", "New Cashier",
                "role", "CASHIER", "temporaryCredential", "111222"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("mustChangeCredential").asBoolean()).isTrue();
        assertThat(created.body().has("credentialHash")).isFalse();

        ApiClient newbie = loggedIn("newbie", "111222");
        Resp blocked = newbie.get("/api/v1/menu");
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.code()).isEqualTo("CREDENTIAL_CHANGE_REQUIRED");
        assertThat(newbie.post("/api/v1/orders/quotes", Map.of("lines", java.util.List.of())).code())
                .isEqualTo("CREDENTIAL_CHANGE_REQUIRED");
        assertThat(newbie.get("/api/v1/auth/me").body().path("mustChangeCredential").asBoolean()).isTrue();

        Resp weak = newbie.post("/api/v1/auth/credential", Map.of("currentCredential", "111222", "newCredential", "12ab"));
        assertThat(weak.status()).isEqualTo(422);
        Resp same = newbie.post("/api/v1/auth/credential", Map.of("currentCredential", "111222", "newCredential", "111222"));
        assertThat(same.status()).isEqualTo(422);
        Resp wrongCurrent = newbie.post("/api/v1/auth/credential", Map.of("currentCredential", "000000", "newCredential", "908172"));
        assertThat(wrongCurrent.status()).isEqualTo(422);

        String before = newbie.sessionCookie().orElseThrow();
        assertThat(newbie.post("/api/v1/auth/credential",
                Map.of("currentCredential", "111222", "newCredential", "908172")).status()).isEqualTo(204);
        assertThat(newbie.sessionCookie().orElseThrow()).isNotEqualTo(before);
        assertThat(newbie.get("/api/v1/menu").status()).isEqualTo(200);
        assertThat(newbie.get("/api/v1/auth/me").body().path("mustChangeCredential").asBoolean()).isFalse();
    }

    @Test
    void credentialPolicyAndDuplicateLogins() {
        ApiClient admin = admin();
        assertThat(admin.post("/api/v1/admin/staff", Map.of("login", "boss2", "displayName", "Boss",
                "role", "ADMIN", "temporaryCredential", "short-pass")).status()).isEqualTo(422);
        assertThat(admin.post("/api/v1/admin/staff", Map.of("login", "c3", "displayName", "C",
                "role", "CASHIER", "temporaryCredential", "12345")).status()).isEqualTo(422);
        assertThat(admin.post("/api/v1/admin/staff", Map.of("login", "CASHIER1", "displayName", "Dup",
                "role", "CASHIER", "temporaryCredential", "123456")).status()).isEqualTo(422);
    }

    @Test
    void lockoutAfterFailedAttemptsAndAuditedResetUnlocks() {
        for (int i = 0; i < 4; i++) {
            assertThat(client().login("cashier1", "000000").status()).isEqualTo(401);
        }
        Resp fifth = client().login("cashier1", "000000");
        assertThat(fifth.status()).isEqualTo(429);
        assertThat(fifth.code()).isEqualTo("TOO_MANY_ATTEMPTS");
        // the right PIN does not unlock
        assertThat(client().login("cashier1", CASHIER_PIN).status()).isEqualTo(429);

        ApiClient admin = admin();
        assertThat(admin.post("/api/v1/admin/staff/" + cashierId + "/credential-reset",
                Map.of("temporaryCredential", "246810")).status()).isEqualTo(204);
        assertThat(count("select count(*) from audit_event where event_type = 'STAFF_CREDENTIAL_RESET'")).isEqualTo(1);

        ApiClient after = loggedIn("cashier1", "246810");
        assertThat(after.get("/api/v1/menu").code()).isEqualTo("CREDENTIAL_CHANGE_REQUIRED");
        assertThat(client().login("cashier1", CASHIER_PIN).status()).isEqualTo(401);
    }

    @Test
    void credentialResetEndsExistingSessions() {
        ApiClient cashier = cashier();
        assertThat(cashier.get("/api/v1/auth/me").status()).isEqualTo(200);
        admin().post("/api/v1/admin/staff/" + cashierId + "/credential-reset", Map.of("temporaryCredential", "135790"));
        assertThat(cashier.get("/api/v1/auth/me").status()).isEqualTo(401);
    }

    @Test
    void addressThrottleStopsBruteForce() {
        for (int i = 0; i < 30; i++) {
            assertThat(client().login("ghost" + i, "123456").status()).isEqualTo(401);
        }
        Resp blocked = client().login("cashier1", CASHIER_PIN);
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.code()).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @Test
    void deactivationEndsSessionsAtOnce() {
        ApiClient cashier = cashier();
        ApiClient admin = admin();
        int revision = staffRevision(admin, cashierId);
        Resp r = admin.patch("/api/v1/admin/staff/" + cashierId, Map.of("active", false, "expectedRevision", revision));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().path("active").asBoolean()).isFalse();
        assertThat(cashier.get("/api/v1/auth/me").status()).isEqualTo(401);
        assertThat(client().login("cashier1", CASHIER_PIN).status()).isEqualTo(401);
    }

    @Test
    void staleStaffRevisionIsVersionConflict() {
        ApiClient admin = admin();
        int revision = staffRevision(admin, cashierId);
        assertThat(admin.patch("/api/v1/admin/staff/" + cashierId,
                Map.of("displayName", "Renamed", "expectedRevision", revision)).status()).isEqualTo(200);
        Resp stale = admin.patch("/api/v1/admin/staff/" + cashierId,
                Map.of("displayName", "Again", "expectedRevision", revision));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void lastActiveAdminCannotBeDisabledOrDemoted() {
        ApiClient admin = admin();
        int revision = staffRevision(admin, adminId);
        Resp disable = admin.patch("/api/v1/admin/staff/" + adminId, Map.of("active", false, "expectedRevision", revision));
        assertThat(disable.status()).isEqualTo(409);
        assertThat(disable.code()).isEqualTo("LAST_ACTIVE_ADMIN");
        Resp demote = admin.patch("/api/v1/admin/staff/" + adminId, Map.of("role", "CASHIER", "expectedRevision", revision));
        assertThat(demote.code()).isEqualTo("LAST_ACTIVE_ADMIN");

        UUID second = insertStaff("admin2", Role.ADMIN, "second-admin-pass", false);
        assertThat(admin.patch("/api/v1/admin/staff/" + second, Map.of("active", false,
                "expectedRevision", staffRevision(admin, second))).status()).isEqualTo(200);
        assertThat(admin.patch("/api/v1/admin/staff/" + adminId, Map.of("active", false, "expectedRevision", revision))
                .code()).isEqualTo("LAST_ACTIVE_ADMIN");
    }

    @Test
    void anonymousCannotCreateAccounts() {
        ApiClient anonymous = client();
        Resp r = anonymous.post("/api/v1/admin/staff", Map.of("login", "intruder", "displayName", "X",
                "role", "ADMIN", "temporaryCredential", "intruder-pass-1"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(count("select count(*) from staff_user where login_normalized = 'intruder'")).isZero();
    }

    @Test
    void logsNeverContainCredentials(CapturedOutput output) {
        client().login("cashier1", "999111");
        ApiClient cashier = cashier();
        cashier.post("/api/v1/auth/credential", Map.of("currentCredential", CASHIER_PIN, "newCredential", "864213"));
        admin().post("/api/v1/admin/staff", Map.of("login", "logcheck", "displayName", "L", "role", "ADMIN",
                "temporaryCredential", "very-secret-temp-77"));
        assertThat(output.getAll()).doesNotContain(CASHIER_PIN, "999111", "864213", ADMIN_PASSWORD, "very-secret-temp-77");
        assertThat(jdbc.sql("select string_agg(metadata::text, ' ') from audit_event").query(String.class).single())
                .doesNotContain(CASHIER_PIN, "999111", "864213", "very-secret-temp-77");
    }

    private int staffRevision(ApiClient admin, UUID id) {
        for (var s : admin.get("/api/v1/admin/staff").body()) {
            if (s.path("id").asString().equals(id.toString())) {
                return s.path("revision").asInt();
            }
        }
        throw new IllegalStateException("staff not found");
    }
}
