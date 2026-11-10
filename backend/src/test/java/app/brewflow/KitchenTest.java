package app.brewflow;

import app.brewflow.kds.TicketStreamHub;
import app.brewflow.support.ApiClient;
import app.brewflow.support.ApiClient.Resp;
import app.brewflow.support.IntegrationTest;
import app.brewflow.support.MenuFixture;
import app.brewflow.support.SseConnection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static app.brewflow.support.MenuFixture.cart;
import static org.assertj.core.api.Assertions.assertThat;

/** Sprint 3 and T021: open-ticket snapshot, section 4.5 matrix, SSE after commit. */
class KitchenTest extends IntegrationTest {

    @Autowired TicketStreamHub hub;

    private ApiClient admin;
    private ApiClient cashier;
    private ApiClient barista;
    private MenuFixture menu;

    @BeforeEach
    void setUp() {
        admin = admin();
        cashier = cashier();
        barista = barista();
        menu = MenuFixture.create(admin);
        openShift(admin);
    }

    @Test
    void kitchenProjectionsCarryNoTenderFields() {
        String orderId = order(menu.line("Latte", 1, "L", "Oat", "Vanilla"));
        Resp kitchen = barista.get("/api/v1/orders/" + orderId + "/kitchen");
        assertThat(kitchen.status()).isEqualTo(200);
        assertThat(kitchen.raw()).doesNotContain("payment", "amount", "externalReference", "total", "TRAINING", "T-1",
                "Price", "price");
        assertThat(kitchen.body().path("lines").get(0).path("modifiers")).hasSize(3);
        assertThat(kitchen.body().path("humanNo").asInt()).isEqualTo(1);

        Resp board = barista.get("/api/v1/kds/open-tickets");
        assertThat(board.body().path("tickets")).hasSize(1);
        assertThat(board.raw()).doesNotContain("payment", "amount", "externalReference", "T-1");
        assertThat(board.body().path("snapshotAt").asString()).isNotBlank();

        assertThat(cashier.get("/api/v1/orders/" + orderId + "/kitchen").status()).isEqualTo(403);
        assertThat(cashier.get("/api/v1/kds/open-tickets").status()).isEqualTo(403);
    }

    @Test
    void emptyBoard() {
        Resp board = barista.get("/api/v1/kds/open-tickets");
        assertThat(board.status()).isEqualTo(200);
        assertThat(board.body().path("tickets")).isEmpty();
    }

    @Test
    void transitionMatrix() {
        String orderId = order(menu.line("Espresso", 1));
        String path = "/api/v1/orders/" + orderId + "/prep-status";

        Resp skip = barista.patch(path, Map.of("prepStatus", "READY", "expectedVersion", 0));
        assertThat(skip.code()).isEqualTo("INVALID_TRANSITION");

        Resp preparing = barista.patch(path, Map.of("prepStatus", "PREPARING", "expectedVersion", 0));
        assertThat(preparing.status()).isEqualTo(200);
        assertThat(preparing.body().path("version").asInt()).isEqualTo(1);

        Resp replay = barista.patch(path, Map.of("prepStatus", "PREPARING", "expectedVersion", 0));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("version").asInt()).as("no double increment").isEqualTo(1);

        Resp stale = barista.patch(path, Map.of("prepStatus", "READY", "expectedVersion", 0));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("VERSION_CONFLICT");

        assertThat(barista.patch(path, Map.of("prepStatus", "READY", "expectedVersion", 1)).body().path("version").asInt())
                .isEqualTo(2);
        Resp back = barista.patch(path, Map.of("prepStatus", "NEW", "expectedVersion", 2));
        assertThat(back.code()).as("READY to NEW is forbidden").isEqualTo("INVALID_TRANSITION");

        Resp baristaCancel = barista.patch(path, Map.of("prepStatus", "CANCELLED", "expectedVersion", 2));
        assertThat(baristaCancel.status()).isEqualTo(403);
        assertThat(baristaCancel.code()).isEqualTo("FORBIDDEN");
        Resp adminCancel = admin.patch(path, Map.of("prepStatus", "CANCELLED", "expectedVersion", 2));
        assertThat(adminCancel.status()).isEqualTo(409);
        assertThat(adminCancel.code()).isEqualTo("INVALID_TRANSITION");
        assertThat(adminCancel.body().path("error").path("details").path("use").asString())
                .isEqualTo("POST /admin/orders/{id}/corrections");

        assertThat(barista.get("/api/v1/kds/open-tickets").body().path("tickets")).hasSize(1);
        Resp cleared = admin.patch(path, Map.of("prepStatus", "CLEARED", "expectedVersion", 2));
        assertThat(cleared.body().path("prepStatus").asString()).isEqualTo("CLEARED");
        assertThat(barista.get("/api/v1/kds/open-tickets").body().path("tickets")).isEmpty();
        assertThat(barista.patch(path, Map.of("prepStatus", "PREPARING", "expectedVersion", 3)).code())
                .isEqualTo("INVALID_TRANSITION");

        assertThat(cashier.patch(path, Map.of("prepStatus", "PREPARING", "expectedVersion", 3)).status()).isEqualTo(403);
        assertThat(barista.patch(path, Map.of("prepStatus", "COOKING", "expectedVersion", 3)).status()).isEqualTo(422);
        assertThat(barista.patch("/api/v1/orders/" + UUID.randomUUID() + "/prep-status",
                Map.of("prepStatus", "PREPARING", "expectedVersion", 0)).status()).isEqualTo(404);
        assertThat(count("select count(*) from audit_event where event_type = 'PREP_STATUS_CHANGED'")).isEqualTo(3);
    }

    @Test
    void openTicketsComeFromTheDatabaseInPlacementOrder() {
        String first = order(menu.line("Espresso", 1));
        String second = order(menu.line("Black tea", 1));
        String third = order(menu.line("Croissant", 1));
        barista.patch("/api/v1/orders/" + second + "/prep-status", Map.of("prepStatus", "PREPARING", "expectedVersion", 0));
        List<String> ids = barista.get("/api/v1/kds/open-tickets").body().path("tickets").valueStream()
                .map(t -> t.path("orderId").asString()).toList();
        assertThat(ids).containsExactly(first, second, third);
    }

    @Test
    void streamSendsAHintAfterCommitOnly() throws Exception {
        try (SseConnection stream = new SseConnection(port(), barista.sessionCookie().orElseThrow())) {
            assertThat(stream.statusLine()).contains("200");
            assertThat(stream.awaitLineContaining(":connected", Duration.ofSeconds(5))).isNotNull();

            String orderId = order(menu.line("Espresso", 1));
            assertThat(stream.awaitLineContaining("event:ticket.changed", Duration.ofSeconds(5))).isNotNull();
            String data = stream.awaitLineContaining("data:", Duration.ofSeconds(5));
            assertThat(data).contains(orderId).contains("\"version\":0").contains("\"type\":\"CREATED\"");
            assertThat(data).doesNotContain("amount", "payment");

            // a rolled-back submit (no shift) emits nothing
            admin.post("/api/v1/admin/shifts/" + currentShift() + "/close", Map.of("countedCash", "0.00"));
            String quoteId = cashier.post("/api/v1/orders/quotes", cart(menu.line("Espresso", 1))).text("quoteId");
            Resp failed = cashier.post("/api/v1/orders", Map.of("quoteId", quoteId, "paymentMethod", "TRAINING",
                    "confirmedExternalAction", true), Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertThat(failed.code()).isEqualTo("SHIFT_CLOSED");
            assertThat(stream.awaitLineContaining("event:ticket.changed", Duration.ofMillis(1500))).isNull();

            barista.patch("/api/v1/orders/" + orderId + "/prep-status", Map.of("prepStatus", "PREPARING", "expectedVersion", 0));
            assertThat(stream.awaitLineContaining("\"version\":1", Duration.ofSeconds(5))).contains("PREP_STATUS");
        }
    }

    @Test
    void cashierCannotOpenTheStream() throws Exception {
        try (SseConnection stream = new SseConnection(port(), cashier.sessionCookie().orElseThrow())) {
            assertThat(stream.statusLine()).contains("403");
        }
        try (SseConnection stream = new SseConnection(port(), "not-a-session")) {
            assertThat(stream.statusLine()).contains("401");
        }
    }

    @Test
    void logoutClosesTheStream() throws Exception {
        try (SseConnection stream = new SseConnection(port(), barista.sessionCookie().orElseThrow())) {
            assertThat(stream.awaitLineContaining(":connected", Duration.ofSeconds(5))).isNotNull();
            assertThat(barista.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
            assertThat(stream.awaitEnd(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void deactivationClosesTheStreamAtOnce() throws Exception {
        try (SseConnection stream = new SseConnection(port(), barista.sessionCookie().orElseThrow())) {
            assertThat(stream.awaitLineContaining(":connected", Duration.ofSeconds(5))).isNotNull();
            int revision = jdbc.sql("select revision from staff_user where id = :id").param("id", baristaId)
                    .query(Integer.class).single();
            assertThat(admin.patch("/api/v1/admin/staff/" + baristaId,
                    Map.of("active", false, "expectedRevision", revision)).status()).isEqualTo(200);
            assertThat(stream.awaitEnd(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void fiftyConnectDisconnectCyclesLeaveNoEmitter() throws Exception {
        // streams closed by earlier tests are found dead on the next write
        drainDeadEmitters();
        assertThat(hub.activeCount()).isZero();
        for (int i = 0; i < 50; i++) {
            try (SseConnection stream = new SseConnection(port(), barista.sessionCookie().orElseThrow())) {
                assertThat(stream.awaitLineContaining(":connected", Duration.ofSeconds(5))).isNotNull();
            }
        }
        drainDeadEmitters();
        assertThat(hub.activeCount()).isZero();
    }

    private void drainDeadEmitters() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (hub.activeCount() > 0 && System.nanoTime() < deadline) {
            hub.heartbeat();
            Thread.sleep(100);
        }
    }

    private String currentShift() {
        return jdbc.sql("select id::text from shift where status = 'OPEN'").query(String.class).single();
    }

    @SafeVarargs
    private String order(Map<String, Object>... lines) {
        Resp q = cashier.post("/api/v1/orders/quotes", cart(lines));
        Resp o = cashier.post("/api/v1/orders", Map.of("quoteId", q.text("quoteId"), "paymentMethod", "TRAINING",
                "externalReference", "T-1", "confirmedExternalAction", true),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(o.status()).as(o.raw()).isEqualTo(201);
        return o.text("id");
    }
}
