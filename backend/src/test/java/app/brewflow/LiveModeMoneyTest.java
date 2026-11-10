package app.brewflow;

import app.brewflow.support.ApiClient;
import app.brewflow.support.ApiClient.Resp;
import app.brewflow.support.IntegrationTest;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sprint 4 in APP_MODE=LIVE (SOP gate set for the test only): section 5 fixtures (T028), voids (T025, T043),
 * orphan-receipt recovery (T011, T044), mode rules (T016). Amounts are fictitious.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${BREWFLOW_TEST_DB_URL:jdbc:postgresql://127.0.0.1:55432/brewflow_test}",
        "server.servlet.session.cookie.secure=false",
        "brewflow.app-mode=LIVE",
        "brewflow.fiscal-sop-approved=true",
        "brewflow.fiscal-sop-id=SOP-TEST-ONLY",
        "brewflow.deployment-tier=test",
        "logging.level.brewflow.access=WARN"
})
class LiveModeMoneyTest extends IntegrationTest {

    private ApiClient admin;
    private ApiClient cashier;
    private final Map<String, UUID> items = new HashMap<>();

    @BeforeEach
    void setUp() {
        admin = admin();
        cashier = cashier();
        UUID category = UUID.fromString(admin.post("/api/v1/admin/menu/categories", Map.of("title", "Fixture")).text("id"));
        for (var e : Map.of("Big tray", "420.00", "Latte pack", "100.00", "Espresso", "50.00", "Cake box", "200.00").entrySet()) {
            items.put(e.getKey(), UUID.fromString(admin.post("/api/v1/admin/menu/items",
                    Map.of("categoryId", category, "title", e.getKey(), "basePrice", e.getValue())).text("id")));
        }
    }

    // ------------------------------------------------------------------ T028

    @Test
    void sectionFiveFixtures() {
        UUID shiftId = openShift(admin);
        String a = order("Big tray", "CASH");
        String b = order("Latte pack", "CASH");
        String c = order("Espresso", "CASH");
        String d = order("Cake box", "EXTERNAL_CARD");
        assertThat(List.of(a, d)).doesNotHaveDuplicates();

        Resp pending = voidNote(b, "PENDING", null);
        assertThat(pending.status()).as(pending.raw()).isEqualTo(201);
        String pendingId = pending.body().path("corrections").get(0).path("id").asString();
        assertThat(pending.body().path("prepStatus").asString()).isEqualTo("NEW");
        Resp confirmed = voidNote(c, "CONFIRMED", null);
        assertThat(confirmed.body().path("prepStatus").asString()).isEqualTo("CANCELLED");

        JsonNode report = daily();
        assertThat(report.path("cashTotal").asString()).isEqualTo("520.00");
        assertThat(report.path("externalCardTotal").asString()).isEqualTo("200.00");
        assertThat(report.path("externalQrTotal").asString()).isEqualTo("0.00");
        assertThat(report.path("recordedTenderTotal").asString()).isEqualTo("720.00");
        assertThat(report.path("pendingExternalCorrections").asLong()).isEqualTo(1);
        assertThat(report.path("topItems").valueStream().map(t -> t.path("title").asString()))
                .contains("Big tray", "Latte pack", "Cake box").doesNotContain("Espresso");
        assertThat(report.path("label").asString()).contains("not a fiscal reconciliation");

        Resp close = admin.post("/api/v1/admin/shifts/" + shiftId + "/close", Map.of("countedCash", "500.00"));
        assertThat(close.status()).as(close.raw()).isEqualTo(200);
        assertThat(close.body().path("report").path("cashTotal").asString()).isEqualTo("520.00");
        assertThat(close.body().path("report").path("variance").asString()).isEqualTo("-20.00");
        assertThat(admin.get("/api/v1/admin/reports/daily?shiftId=" + shiftId).body().path("variance").asString())
                .isEqualTo("-20.00");

        Resp confirm = voidNote(b, "CONFIRMED", pendingId);
        assertThat(confirm.status()).as(confirm.raw()).isEqualTo(201);
        assertThat(confirm.body().path("prepStatus").asString()).isEqualTo("CANCELLED");

        JsonNode after = daily();
        assertThat(after.path("cashTotal").asString()).isEqualTo("420.00");
        assertThat(after.path("recordedTenderTotal").asString()).isEqualTo("620.00");
        assertThat(after.path("pendingExternalCorrections").asLong()).isZero();
        assertThat(after.path("countedCash").asString()).isEqualTo("500.00");
        assertThat(after.path("variance").asString()).isEqualTo("80.00");

        Resp secondClose = admin.post("/api/v1/admin/shifts/" + shiftId + "/close", Map.of("countedCash", "500.00"));
        assertThat(secondClose.status()).isEqualTo(409);
        assertThat(admin.post("/api/v1/admin/shifts/" + UUID.randomUUID() + "/close",
                Map.of("countedCash", "1.00")).status()).isEqualTo(404);
        // the original order, lines and payment row stay
        assertThat(count("select count(*) from payment_record")).isEqualTo(4);
    }

    @Test
    void shiftRules() {
        openShift(admin);
        assertThat(admin.post("/api/v1/admin/shifts/open", null).status()).isEqualTo(409);
        assertThat(cashier.post("/api/v1/admin/shifts/open", null).status()).isEqualTo(403);
        JsonNode empty = daily();
        assertThat(empty.path("cashTotal").asString()).isEqualTo("0.00");
        assertThat(empty.path("orderCount").asLong()).isZero();
        assertThat(empty.path("variance").isNull()).isTrue();
    }

    @Test
    void adminDayListShowsVoidStateAndIsAdminOnly() {
        openShift(admin);
        String plain = order("Big tray", "CASH");
        String pending = order("Latte pack", "CASH");
        String voided = order("Espresso", "EXTERNAL_QR");
        voidNote(pending, "PENDING", null);
        voidNote(voided, "CONFIRMED", null);

        assertThat(admin.get("/api/v1/auth/me").text("businessDate")).isEqualTo(time.today().toString());
        Resp list = admin.get("/api/v1/admin/orders?businessDate=" + time.today());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).hasSize(3);
        Map<String, JsonNode> byId = new HashMap<>();
        list.body().forEach(o -> byId.put(o.path("id").asString(), o));
        assertThat(byId.get(plain).path("voidState").isNull()).isTrue();
        assertThat(byId.get(plain).path("total").asString()).isEqualTo("420.00");
        assertThat(byId.get(plain).path("cashierName").asString()).isEqualTo("Test cashier1");
        assertThat(byId.get(pending).path("voidState").asString()).isEqualTo("PENDING");
        assertThat(byId.get(voided).path("voidState").asString()).isEqualTo("CONFIRMED");
        assertThat(byId.get(voided).path("paymentMethod").asString()).isEqualTo("EXTERNAL_QR");
        assertThat(list.body().get(0).path("humanNo").asInt()).isEqualTo(3);

        assertThat(cashier.get("/api/v1/admin/orders?businessDate=" + time.today()).status()).isEqualTo(403);
        assertThat(admin.get("/api/v1/admin/orders?businessDate=" + time.today().minusDays(1)).body()).isEmpty();
    }

    // ------------------------------------------------------------------ T016

    @Test
    void liveRejectsTheTrainingMethod() {
        openShift(admin);
        String quoteId = quote("Espresso");
        Resp r = submit(UUID.randomUUID(), quoteId, "TRAINING");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("MODE_REJECTED");
        Resp ok = submit(UUID.randomUUID(), quoteId, "EXTERNAL_QR");
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().path("recordStatus").asString()).isEqualTo("RECORDED");
    }

    // ------------------------------------------------------------------ T025, T043

    @Test
    void confirmedVoidLeavesAClearedTicketCleared() {
        openShift(admin);
        String orderId = order("Cake box", "CASH");
        ApiClient barista = barista();
        String path = "/api/v1/orders/" + orderId + "/prep-status";
        barista.patch(path, Map.of("prepStatus", "PREPARING", "expectedVersion", 0));
        barista.patch(path, Map.of("prepStatus", "READY", "expectedVersion", 1));
        barista.patch(path, Map.of("prepStatus", "CLEARED", "expectedVersion", 2));
        Resp voided = voidNote(orderId, "CONFIRMED", null);
        assertThat(voided.body().path("prepStatus").asString()).isEqualTo("CLEARED");
        assertThat(voided.body().path("version").asInt()).isEqualTo(3);
        assertThat(daily().path("cashTotal").asString()).isEqualTo("0.00");
    }

    @Test
    void confirmedVoidCancelsAnOpenTicketAndBumpsTheVersion() {
        openShift(admin);
        String orderId = order("Cake box", "CASH");
        barista().patch("/api/v1/orders/" + orderId + "/prep-status", Map.of("prepStatus", "PREPARING", "expectedVersion", 0));
        Resp voided = voidNote(orderId, "CONFIRMED", null);
        assertThat(voided.body().path("prepStatus").asString()).isEqualTo("CANCELLED");
        assertThat(voided.body().path("version").asInt()).isEqualTo(2);
        assertThat(barista().get("/api/v1/kds/open-tickets").body().path("tickets")).isEmpty();
    }

    @Test
    void voidConfirmationRules() {
        openShift(admin);
        String orderId = order("Latte pack", "CASH");
        String pendingId = voidNote(orderId, "PENDING", null).body().path("corrections").get(0).path("id").asString();
        assertThat(daily().path("cashTotal").asString()).as("pending void keeps cash").isEqualTo("100.00");

        assertThat(voidNote(orderId, "PENDING", null).code()).isEqualTo("ALREADY_VOIDED");
        assertThat(voidNote(orderId, "CONFIRMED", UUID.randomUUID().toString()).status()).isEqualTo(422);
        assertThat(voidNote(orderId, "NOT_APPLICABLE", null).code()).isEqualTo("MODE_REJECTED");

        Resp confirmed = voidNote(orderId, "CONFIRMED", pendingId);
        assertThat(confirmed.status()).isEqualTo(201);
        assertThat(confirmed.body().path("corrections")).hasSize(2);
        assertThat(confirmed.body().path("corrections").get(1).path("confirmsCorrectionId").asString()).isEqualTo(pendingId);
        assertThat(daily().path("pendingExternalCorrections").asLong()).isZero();
        assertThat(voidNote(orderId, "CONFIRMED", null).code()).isEqualTo("ALREADY_VOIDED");
        assertThat(voidNote(orderId, "PENDING", null).code()).isEqualTo("ALREADY_VOIDED");

        for (String type : List.of("REVERSAL_NOTE", "ADJUSTMENT_NOTE")) {
            Resp r = admin.post("/api/v1/admin/orders/" + orderId + "/corrections",
                    Map.of("type", type, "externalActionState", "CONFIRMED", "reason", "partial"));
            assertThat(r.status()).isEqualTo(422);
            assertThat(r.code()).isEqualTo("INVALID_INPUT");
        }
        assertThat(cashier.post("/api/v1/admin/orders/" + orderId + "/corrections",
                Map.of("type", "VOID_NOTE", "externalActionState", "PENDING", "reason", "x")).status()).isEqualTo(403);
        assertThat(voidNote(UUID.randomUUID().toString(), "PENDING", null).status()).isEqualTo(404);
        assertThatThrownBy(() -> jdbc.sql("update order_correction set reason = 'edited'").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void directConfirmationOfAnOrderWithAPendingNoteDropsThePendingCount() {
        openShift(admin);
        String orderId = order("Latte pack", "CASH");
        voidNote(orderId, "PENDING", null);
        voidNote(orderId, "CONFIRMED", null);
        assertThat(daily().path("pendingExternalCorrections").asLong()).isZero();
        assertThat(daily().path("cashTotal").asString()).isEqualTo("0.00");
    }

    // ------------------------------------------------------------------ T011, T044

    @Test
    void orphanReceiptRecoveryWithTheCashiersKey() {
        openShift(admin);
        String consumed = quote("Cake box");
        assertThat(submit(UUID.randomUUID(), consumed, "CASH").status()).isEqualTo(201);
        String orphan = quote("Latte pack");
        UUID cashierKey = UUID.randomUUID();
        clock.advance(Duration.ofMinutes(11));

        Resp expired = submit(cashierKey, orphan, "CASH");
        assertThat(expired.code()).isEqualTo("QUOTE_EXPIRED");
        String stillValid = quote("Espresso");

        Resp list = admin.get(lookupPath());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).hasSize(1);
        JsonNode row = list.body().get(0);
        assertThat(row.path("quoteId").asString()).isEqualTo(orphan);
        assertThat(row.path("quoteRef").asString()).isEqualTo(orphan.substring(orphan.length() - 6).toUpperCase());
        assertThat(row.path("total").asString()).isEqualTo("100.00");
        assertThat(row.path("cashierName").asString()).isEqualTo("Test cashier1");
        assertThat(cashier.get(lookupPath()).status()).isEqualTo(403);
        assertThat(barista().get(lookupPath()).status()).isEqualTo(403);
        assertThat(admin.get("/api/v1/admin/quotes?status=ALL&businessDate=" + time.today()).status()).isEqualTo(422);

        assertThat(recover(stillValid, "50.00", "CASH", null).code()).isEqualTo("QUOTE_STILL_VALID");
        assertThat(recover(orphan, "100", "CASH", null).status()).isEqualTo(422);
        assertThat(recover(orphan, "100.00", "TRAINING", null).code()).isEqualTo("MODE_REJECTED");
        assertThat(cashier.post("/api/v1/admin/quotes/" + orphan + "/recover", recoverBody("100.00", "CASH", null))
                .status()).isEqualTo(403);

        long ordersBefore = count("select count(*) from orders");
        Resp mismatch = recover(orphan, "120.00", "CASH", cashierKey);
        assertThat(mismatch.status()).isEqualTo(409);
        assertThat(mismatch.code()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(mismatch.body().path("error").path("message").asString()).contains("Do not issue a second receipt");
        assertThat(count("select count(*) from orders")).isEqualTo(ordersBefore);
        assertThat(count("select count(*) from external_action_exception where state = 'PENDING'")).isEqualTo(1);
        assertThat(admin.get(lookupPath()).body().get(0).path("pendingExceptionIds")).hasSize(1);

        Resp recovered = recover(orphan, "100.00", "CASH", cashierKey);
        assertThat(recovered.status()).as(recovered.raw()).isEqualTo(201);
        assertThat(recovered.text("instruction")).isEqualTo("DO_NOT_ISSUE_ANOTHER_FISCAL_RECEIPT");
        JsonNode order = recovered.body().path("order");
        String orderId = order.path("id").asString();
        assertThat(order.path("recordStatus").asString()).isEqualTo("EXCEPTION");
        assertThat(order.path("cashierId").asString()).isEqualTo(cashierId.toString());
        assertThat(order.path("createdBy").asString()).isEqualTo(adminId.toString());
        assertThat(order.path("total").asString()).isEqualTo("100.00");

        Resp again = recover(orphan, "100.00", "CASH", cashierKey);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("order").path("id").asString()).isEqualTo(orderId);
        assertThat(count("select count(*) from orders")).isEqualTo(ordersBefore + 1);

        Resp retry = submit(cashierKey, orphan, "CASH");
        assertThat(retry.status()).as(retry.raw()).isEqualTo(200);
        assertThat(retry.text("id")).isEqualTo(orderId);
        assertThat(cashier.get("/api/v1/orders/by-key/" + cashierKey).text("id")).isEqualTo(orderId);
        assertThat(cashier.get("/api/v1/orders/" + orderId).status()).isEqualTo(200);

        assertThat(admin.get(lookupPath()).body()).isEmpty();
        assertThat(daily().path("cashTotal").asString()).as("EXCEPTION payment counts").isEqualTo("300.00");
        assertThat(daily().path("exceptionOrderCount").asLong()).isEqualTo(1);
        assertThat(barista().get("/api/v1/kds/open-tickets").body().path("tickets").valueStream()
                .map(t -> t.path("orderId").asString())).contains(orderId);
    }

    @Test
    void recoveryWithoutTheKeyPointsTheCashierToTheOrder() {
        openShift(admin);
        String orphan = quote("Espresso");
        clock.advance(Duration.ofMinutes(11));
        Resp recovered = recover(orphan, "50.00", "EXTERNAL_CARD", null);
        assertThat(recovered.status()).isEqualTo(201);
        String orderId = recovered.body().path("order").path("id").asString();

        Resp retry = submit(UUID.randomUUID(), orphan, "EXTERNAL_CARD");
        assertThat(retry.code()).isEqualTo("QUOTE_ALREADY_CONSUMED");
        assertThat(retry.body().path("error").path("details").path("orderId").asString()).isEqualTo(orderId);
    }

    @Test
    void mismatchCanBeClosedOutsideWithoutAnOrder() {
        openShift(admin);
        String orphan = quote("Espresso");
        clock.advance(Duration.ofMinutes(11));
        String exceptionId = recover(orphan, "49.99", "CASH", null).body().path("error").path("details")
                .path("exceptionId").asString();
        String path = "/api/v1/admin/quotes/exceptions/" + exceptionId + "/resolve-outside";
        assertThat(admin.post(path, null).status()).isEqualTo(204);
        assertThat(admin.post(path, null).status()).isEqualTo(409);
        assertThat(admin.post("/api/v1/admin/quotes/exceptions/" + UUID.randomUUID() + "/resolve-outside", null)
                .status()).isEqualTo(404);
        assertThat(count("select count(*) from orders")).isZero();
        assertThat(count("select count(*) from external_action_exception where state = 'RESOLVED_OUTSIDE'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String lookupPath() {
        return "/api/v1/admin/quotes?status=EXPIRED_UNCONSUMED&businessDate=" + time.today();
    }

    private JsonNode daily() {
        Resp r = admin.get("/api/v1/admin/reports/daily?businessDate=" + time.today());
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return r.body();
    }

    private String quote(String item) {
        Resp q = cashier.post("/api/v1/orders/quotes", Map.of("lines", List.of(
                Map.of("menuItemId", items.get(item), "quantity", 1))));
        assertThat(q.status()).as(q.raw()).isEqualTo(201);
        return q.text("quoteId");
    }

    private Resp submit(UUID key, String quoteId, String method) {
        return cashier.post("/api/v1/orders", Map.of("quoteId", quoteId, "paymentMethod", method,
                "externalReference", "KKM-77", "confirmedExternalAction", true), Map.of("Idempotency-Key", key.toString()));
    }

    private String order(String item, String method) {
        Resp r = submit(UUID.randomUUID(), quote(item), method);
        assertThat(r.status()).as(r.raw()).isEqualTo(201);
        return r.text("id");
    }

    private Resp voidNote(String orderId, String state, String confirms) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "VOID_NOTE");
        body.put("externalActionState", state);
        body.put("reason", "Customer left");
        if (confirms != null) {
            body.put("confirmsCorrectionId", confirms);
        }
        return admin.post("/api/v1/admin/orders/" + orderId + "/corrections", body);
    }

    private Map<String, Object> recoverBody(String amount, String method, UUID key) {
        Map<String, Object> body = new HashMap<>();
        body.put("attestedAmount", amount);
        body.put("paymentMethod", method);
        body.put("externalReference", "KKM-77");
        body.put("reason", "Receipt printed, tablet timed out");
        if (key != null) {
            body.put("idempotencyKey", key.toString());
        }
        return body;
    }

    private Resp recover(String quoteId, String amount, String method, UUID key) {
        return admin.post("/api/v1/admin/quotes/" + quoteId + "/recover", recoverBody(amount, method, key));
    }
}
