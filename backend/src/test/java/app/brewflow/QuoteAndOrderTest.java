package app.brewflow;

import app.brewflow.support.ApiClient;
import app.brewflow.support.ApiClient.Resp;
import app.brewflow.support.IntegrationTest;
import app.brewflow.support.MenuFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

import static app.brewflow.support.MenuFixture.cart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sprint 2: server-priced quotes, idempotent order submit, lost-response recovery (APP_MODE=TRAINING). */
class QuoteAndOrderTest extends IntegrationTest {

    private ApiClient admin;
    private ApiClient cashier;
    private MenuFixture menu;

    @BeforeEach
    void setUpMenu() {
        admin = admin();
        cashier = cashier();
        menu = MenuFixture.create(admin);
    }

    // ------------------------------------------------------------------ BF-S2-03: 20 fixtures to the cent

    @Test
    void twentyFixturesMatchToTheCentWithNoTaxLine() {
        Map<Map<String, Object>, String> fixtures = new java.util.LinkedHashMap<>();
        fixtures.put(cart(menu.line("Espresso", 1)), "120.00");
        fixtures.put(cart(menu.line("Espresso", 2, "Shot")), "310.00");
        fixtures.put(cart(menu.line("Americano", 1, "Oat")), "185.50");
        fixtures.put(cart(menu.line("Cappuccino", 1, "S")), "180.00");
        fixtures.put(cart(menu.line("Cappuccino", 1, "M", "Almond")), "275.25");
        fixtures.put(cart(menu.line("Latte", 1, "L", "Oat", "Vanilla")), "330.50");
        fixtures.put(cart(menu.line("Latte", 3, "M", "Caramel", "Hazelnut")), "847.50");
        fixtures.put(cart(menu.line("Flat white", 1, "S", "Lactose-free")), "230.00");
        fixtures.put(cart(menu.line("Raf", 2, "L", "Vanilla", "Caramel")), "700.00");
        fixtures.put(cart(menu.line("Black tea", 1)), "90.00");
        fixtures.put(cart(menu.line("Black tea", 4)), "360.00");
        fixtures.put(cart(menu.line("Matcha latte", 1, "M", "Almond")), "345.25");
        fixtures.put(cart(menu.line("Croissant", 2)), "220.00");
        fixtures.put(cart(menu.line("Cheesecake", 1), menu.line("Croissant", 1)), "370.00");
        fixtures.put(cart(menu.line("Americano", 2, "Shot", "Hazelnut")), "405.00");
        fixtures.put(cart(menu.line("Latte", 1, "S", "Shot")), "225.00");
        fixtures.put(cart(menu.line("Cappuccino", 2, "L", "Oat"), menu.line("Espresso", 1)), "711.00");
        fixtures.put(cart(menu.line("Raf", 1, "M", "Almond", "Hazelnut")), "352.75");
        fixtures.put(cart(menu.line("Flat white", 3, "L", "Oat")), "946.50");
        fixtures.put(cart(menu.line("Matcha latte", 1, "S"), menu.line("Black tea", 1), menu.line("Cheesecake", 2)), "860.00");
        assertThat(fixtures).hasSize(20);

        for (var fixture : fixtures.entrySet()) {
            Resp q = cashier.post("/api/v1/orders/quotes", fixture.getKey());
            assertThat(q.status()).as(q.raw()).isEqualTo(201);
            assertThat(q.text("total")).as(q.raw()).isEqualTo(fixture.getValue());
            assertThat(fieldNames(q.body())).noneMatch(name -> name.toLowerCase().contains("tax"));
            String id = q.text("quoteId");
            assertThat(q.text("quoteRef")).isEqualTo(id.substring(id.length() - 6).toUpperCase());
        }

        Resp latte = cashier.post("/api/v1/orders/quotes", cart(menu.line("Latte", 3, "M", "Caramel", "Hazelnut")));
        JsonNode line = latte.body().path("lines").get(0);
        assertThat(line.path("unitBasePrice").asString()).isEqualTo("190.00");
        assertThat(line.path("unitPrice").asString()).isEqualTo("282.50");
        assertThat(line.path("lineTotal").asString()).isEqualTo("847.50");
        assertThat(line.path("modifiers")).hasSize(3);
    }

    @Test
    void tamperedClientAmountsAreIgnored() {
        Map<String, Object> line = new HashMap<>(menu.line("Espresso", 1));
        line.put("unitPrice", "0.01");
        line.put("lineTotal", "0.01");
        Resp q = cashier.post("/api/v1/orders/quotes", Map.of("lines", List.of(line), "total", "0.01", "tax", "0.00"));
        assertThat(q.status()).isEqualTo(201);
        assertThat(q.text("total")).isEqualTo("120.00");
    }

    @Test
    void quoteValidation() {
        assertThat(cashier.post("/api/v1/orders/quotes", cart(menu.line("Muffin", 1))).code()).isEqualTo("INVALID_INPUT");
        assertThat(cashier.post("/api/v1/orders/quotes", cart(menu.line("Latte", 1))).code())
                .as("required size missing").isEqualTo("MODIFIER_RULE_VIOLATION");
        assertThat(cashier.post("/api/v1/orders/quotes", cart(menu.line("Latte", 1, "S", "M"))).code())
                .as("two sizes").isEqualTo("MODIFIER_RULE_VIOLATION");
        assertThat(cashier.post("/api/v1/orders/quotes", cart(menu.line("Black tea", 1, "Oat"))).code())
                .as("option not attached").isEqualTo("MODIFIER_RULE_VIOLATION");
        assertThat(cashier.post("/api/v1/orders/quotes", cart(menu.line("Espresso", 0))).code()).isEqualTo("INVALID_QUANTITY");
        assertThat(cashier.post("/api/v1/orders/quotes", cart(menu.line("Espresso", 100))).code()).isEqualTo("INVALID_QUANTITY");
        assertThat(cashier.post("/api/v1/orders/quotes", Map.of("lines", List.of())).code()).isEqualTo("INVALID_INPUT");
        List<Map<String, Object>> oversized = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            oversized.add(menu.line("Espresso", 1));
        }
        Resp big = cashier.post("/api/v1/orders/quotes", Map.of("lines", oversized));
        assertThat(big.status()).isEqualTo(422);
        assertThat(big.code()).isEqualTo("INVALID_INPUT");
        assertThat(count("select count(*) from order_quote")).isZero();
    }

    // ------------------------------------------------------------------ BF-S2-05: submit

    @Test
    void attestationIsRequiredAndWritesNothing() {
        openShift(admin);
        String quoteId = quote(menu.line("Espresso", 1));
        Map<String, Object> body = new HashMap<>(submitBody(quoteId));
        body.put("confirmedExternalAction", false);
        Resp r = submit(cashier, UUID.randomUUID(), body);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("ATTESTATION_REQUIRED");
        body.remove("confirmedExternalAction");
        assertThat(submit(cashier, UUID.randomUUID(), body).code()).isEqualTo("ATTESTATION_REQUIRED");
        assertNoOrders();
    }

    @Test
    void idempotencyKeyMustBeAUuid() {
        openShift(admin);
        String quoteId = quote(menu.line("Espresso", 1));
        Resp missing = cashier.post("/api/v1/orders", submitBody(quoteId));
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.code()).isEqualTo("INVALID_INPUT");
        Resp bad = cashier.post("/api/v1/orders", submitBody(quoteId), Map.of("Idempotency-Key", "not-a-uuid"));
        assertThat(bad.code()).isEqualTo("INVALID_INPUT");
        assertNoOrders();
    }

    @Test
    void noOpenShiftIsShiftClosed() {
        String quoteId = quote(menu.line("Espresso", 1));
        Resp r = submit(cashier, UUID.randomUUID(), submitBody(quoteId));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("SHIFT_CLOSED");
        assertNoOrders();
    }

    @Test
    void trainingModeRejectsLiveMethods() {
        openShift(admin);
        String quoteId = quote(menu.line("Espresso", 1));
        for (String method : List.of("CASH", "EXTERNAL_CARD", "EXTERNAL_QR")) {
            Resp r = submit(cashier, UUID.randomUUID(), Map.of("quoteId", quoteId, "paymentMethod", method,
                    "confirmedExternalAction", true));
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.code()).isEqualTo("MODE_REJECTED");
        }
        assertNoOrders();
    }

    @Test
    void submitCreatesOneImmutableOrderAndSameKeyReplays() {
        openShift(admin);
        String quoteId = quote(menu.line("Latte", 1, "M", "Oat"), menu.line("Croissant", 2));
        UUID key = UUID.randomUUID();
        Resp created = submit(cashier, key, submitBody(quoteId));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        JsonNode order = created.body();
        assertThat(order.path("recordStatus").asString()).isEqualTo("TRAINING");
        assertThat(order.path("prepStatus").asString()).isEqualTo("NEW");
        assertThat(order.path("version").asInt()).isZero();
        assertThat(order.path("humanNo").asInt()).isEqualTo(1);
        assertThat(order.path("total").asString()).isEqualTo("495.50");
        assertThat(order.path("payment").path("amount").asString()).isEqualTo("495.50");
        assertThat(order.path("payment").path("verification").asString()).isEqualTo("RECORDED_BY_STAFF_NOT_VERIFIED");
        assertThat(order.path("lines")).hasSize(2);

        Resp replay = submit(cashier, key, submitBody(quoteId));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.text("id")).isEqualTo(order.path("id").asString());

        Resp otherBody = submit(cashier, key, Map.of("quoteId", quoteId, "paymentMethod", "TRAINING",
                "externalReference", "changed", "confirmedExternalAction", true));
        assertThat(otherBody.status()).isEqualTo(409);
        assertThat(otherBody.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        Resp secondKey = submit(cashier, UUID.randomUUID(), submitBody(quoteId));
        assertThat(secondKey.code()).isEqualTo("QUOTE_ALREADY_CONSUMED");
        assertThat(secondKey.body().path("error").path("details").path("orderId").asString())
                .isEqualTo(order.path("id").asString());

        Resp adminSecondKey = submit(admin, UUID.randomUUID(), submitBody(quoteId));
        assertThat(adminSecondKey.code()).isEqualTo("QUOTE_ALREADY_CONSUMED");
        assertThat(adminSecondKey.body().path("error").path("details").has("orderId")).isFalse();

        assertThat(count("select count(*) from orders")).isEqualTo(1);
        assertThat(count("select count(*) from payment_record")).isEqualTo(1);
        assertThat(count("select count(*) from order_line")).isEqualTo(2);

        String second = quote(menu.line("Espresso", 1));
        assertThat(submit(cashier, UUID.randomUUID(), submitBody(second)).body().path("humanNo").asInt()).isEqualTo(2);
    }

    @Test
    void anotherCashiersQuoteIsNotFound() {
        openShift(admin);
        String quoteId = quote(menu.line("Espresso", 1));
        Resp r = submit(cashier2(), UUID.randomUUID(), submitBody(quoteId));
        assertThat(r.status()).isEqualTo(404);
        assertNoOrders();
    }

    @Test
    void expiredQuoteCreatesNoOrder() {
        openShift(admin);
        String quoteId = quote(menu.line("Espresso", 1));
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        Resp r = submit(cashier, UUID.randomUUID(), submitBody(quoteId));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("QUOTE_EXPIRED");
        assertThat(r.body().path("error").path("message").asString()).contains("Do not repeat the fiscal action");
        assertThat(r.body().path("error").path("details").path("quoteRef").asString())
                .isEqualTo(quoteId.substring(quoteId.length() - 6).toUpperCase());
        assertNoOrders();
    }

    @Test
    void twentyParallelSameKeyPostsCreateOneOrder() throws Exception {
        openShift(admin);
        String quoteId = quote(menu.line("Cappuccino", 1, "M"));
        UUID key = UUID.randomUUID();
        List<Resp> results = parallel(20, () -> submit(cashier, key, submitBody(quoteId)));
        assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.raw()).isIn(200, 201));
        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(results.stream().map(r -> r.text("id")).collect(Collectors.toSet())).hasSize(1);
        assertThat(count("select count(*) from orders")).isEqualTo(1);
        assertThat(count("select count(*) from payment_record")).isEqualTo(1);
        assertThat(count("select count(*) from idempotency_record")).isEqualTo(1);
    }

    @Test
    void twentyParallelDifferentKeysConsumeTheQuoteOnce() throws Exception {
        openShift(admin);
        String quoteId = quote(menu.line("Cappuccino", 1, "M"));
        List<Resp> results = parallel(20, () -> submit(cashier, UUID.randomUUID(), submitBody(quoteId)));
        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(results.stream().filter(r -> "QUOTE_ALREADY_CONSUMED".equals(r.code()))).hasSize(19);
        assertThat(count("select count(*) from orders")).isEqualTo(1);
    }

    @Test
    void lostResponseIsRecoveredByKey() {
        openShift(admin);
        String quoteId = quote(menu.line("Espresso", 1));
        UUID key = UUID.randomUUID();
        String orderId = submit(cashier, key, submitBody(quoteId)).text("id");

        Resp byKey = cashier.get("/api/v1/orders/by-key/" + key);
        assertThat(byKey.status()).isEqualTo(200);
        assertThat(byKey.text("id")).isEqualTo(orderId);
        assertThat(cashier2().get("/api/v1/orders/by-key/" + key).status()).isEqualTo(404);
        assertThat(admin.get("/api/v1/orders/by-key/" + key).text("id")).isEqualTo(orderId);
        assertThat(cashier.get("/api/v1/orders/by-key/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(barista().get("/api/v1/orders/by-key/" + key).status()).isEqualTo(403);
    }

    @Test
    void orderReadsFollowOwnership() {
        openShift(admin);
        String orderId = submit(cashier, UUID.randomUUID(), submitBody(quote(menu.line("Espresso", 1)))).text("id");
        assertThat(cashier.get("/api/v1/orders/" + orderId).status()).isEqualTo(200);
        assertThat(cashier2().get("/api/v1/orders/" + orderId).status()).isEqualTo(404);
        assertThat(barista().get("/api/v1/orders/" + orderId).status()).isEqualTo(403);
        assertThat(admin.get("/api/v1/orders/" + orderId).status()).isEqualTo(200);
        assertThat(cashier.get("/api/v1/orders/not-a-uuid").status()).isEqualTo(404);
        assertThat(client().get("/api/v1/orders/" + orderId).status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ BF-S2-01 / S2-07 / S4-06: menu

    @Test
    void lineSnapshotsSurviveALaterMenuPriceEdit() {
        openShift(admin);
        String orderId = submit(cashier, UUID.randomUUID(), submitBody(quote(menu.line("Espresso", 2)))).text("id");
        int revision = menu.itemRevision(admin, "Espresso");
        Resp edit = admin.patch("/api/v1/admin/menu/items/" + menu.items.get("Espresso"),
                Map.of("basePrice", "999.00", "title", "Espresso Deluxe", "expectedRevision", revision));
        assertThat(edit.status()).isEqualTo(200);

        JsonNode order = cashier.get("/api/v1/orders/" + orderId).body();
        assertThat(order.path("total").asString()).isEqualTo("240.00");
        assertThat(order.path("lines").get(0).path("title").asString()).isEqualTo("Espresso");
        assertThat(order.path("lines").get(0).path("unitBasePrice").asString()).isEqualTo("120.00");
        assertThat(cashier.post("/api/v1/orders/quotes", cart(menu.line("Espresso", 1))).text("total")).isEqualTo("999.00");

        assertThatThrownBy(() -> jdbc.sql("update order_line set quantity = 5").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("delete from payment_record").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void staleRevisionNegativePricesAndCashierAccess() {
        int revision = menu.itemRevision(admin, "Latte");
        String path = "/api/v1/admin/menu/items/" + menu.items.get("Latte");
        assertThat(admin.patch(path, Map.of("title", "Latte 2", "expectedRevision", revision)).status()).isEqualTo(200);
        Resp stale = admin.patch(path, Map.of("title", "Latte 3", "expectedRevision", revision));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("MENU_CHANGED");

        assertThat(admin.post("/api/v1/admin/menu/items", Map.of("categoryId", menu.categories.get("Food"),
                "title", "Bad", "basePrice", "-1.00")).status()).isEqualTo(422);
        assertThat(admin.post("/api/v1/admin/menu/modifier-options", Map.of("groupId", menu.groups.get("Milk"),
                "title", "Discount", "priceDelta", "-5.00")).status()).isEqualTo(422);
        assertThat(admin.post("/api/v1/admin/menu/modifier-groups", Map.of("title", "Broken",
                "minSelected", 2, "maxSelected", 1)).status()).isEqualTo(422);
        assertThat(cashier.patch(path, Map.of("title", "Hack", "expectedRevision", revision + 1)).status()).isEqualTo(403);
    }

    @Test
    void availabilityToggleStopsNewQuotesButOpenQuoteStaysValid() {
        openShift(admin);
        String openQuote = quote(menu.line("Croissant", 1));
        int revision = menu.itemRevision(admin, "Croissant");
        admin.patch("/api/v1/admin/menu/items/" + menu.items.get("Croissant"),
                Map.of("available", false, "expectedRevision", revision));
        assertThat(cashier.post("/api/v1/orders/quotes", cart(menu.line("Croissant", 1))).code()).isEqualTo("INVALID_INPUT");
        assertThat(submit(cashier, UUID.randomUUID(), submitBody(openQuote)).status()).isEqualTo(201);

        JsonNode croissant = findItem(cashier.get("/api/v1/menu").body(), "Croissant");
        assertThat(croissant.path("available").asBoolean()).isFalse();
    }

    @Test
    void cashierMenuHidesInactiveRows() {
        int revision = menu.itemRevision(admin, "Raf");
        admin.patch("/api/v1/admin/menu/items/" + menu.items.get("Raf"), Map.of("active", false, "expectedRevision", revision));
        JsonNode catalog = cashier.get("/api/v1/menu").body();
        assertThat(findItem(catalog, "Raf")).isNull();
        assertThat(catalog.path("revision").asLong()).isPositive();
        assertThat(catalog.path("currency").asString()).isEqualTo("KGS");
    }

    // ------------------------------------------------------------------ helpers

    private String quote(Map<String, Object>... lines) {
        Resp q = cashier.post("/api/v1/orders/quotes", cart(lines));
        assertThat(q.status()).as(q.raw()).isEqualTo(201);
        return q.text("quoteId");
    }

    private static Map<String, Object> submitBody(String quoteId) {
        return Map.of("quoteId", quoteId, "paymentMethod", "TRAINING", "externalReference", "T-1",
                "confirmedExternalAction", true);
    }

    private static Resp submit(ApiClient client, UUID key, Map<String, Object> body) {
        return client.post("/api/v1/orders", body, Map.of("Idempotency-Key", key.toString()));
    }

    private void assertNoOrders() {
        assertThat(count("select count(*) from orders")).isZero();
        assertThat(count("select count(*) from payment_record")).isZero();
        assertThat(count("select count(*) from idempotency_record")).isZero();
    }

    private static List<Resp> parallel(int n, Callable<Resp> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<Resp>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                calls.add(call);
            }
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : pool.invokeAll(calls)) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static JsonNode findItem(JsonNode catalog, String title) {
        for (JsonNode c : catalog.path("categories")) {
            for (JsonNode i : c.path("items")) {
                if (i.path("title").asString().equals(title)) {
                    return i;
                }
            }
        }
        return null;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        collect(node, names);
        return names;
    }

    private static void collect(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            for (var e : node.properties()) {
                names.add(e.getKey());
                collect(e.getValue(), names);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, names));
        }
    }
}
