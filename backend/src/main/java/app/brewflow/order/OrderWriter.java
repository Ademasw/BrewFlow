package app.brewflow.order;

import app.brewflow.common.ApiException;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.Json;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.order.QuoteRepository.QuoteRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes one order from a frozen quote: order, line snapshots, one payment_record. Used by normal submit and by
 * admin recovery, always inside the caller's transaction.
 */
@Component
public class OrderWriter {

    public record OpenShift(UUID id, LocalDate businessDate) {
    }

    public record IdempotencyRow(UUID actorId, UUID key, String requestHash, String source, UUID orderId) {
    }

    private final JdbcClient jdbc;
    private final Json json;
    private final TimeSource time;

    public OrderWriter(JdbcClient jdbc, Json json, TimeSource time) {
        this.jdbc = jdbc;
        this.json = json;
        this.time = time;
    }

    /** Serializes every request that uses one (actor, key) pair until the holder commits. */
    public void lockKey(UUID actorId, UUID key) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", actorId + ":" + key).query((rs, n) -> 1).single();
    }

    public Optional<IdempotencyRow> findKey(UUID actorId, UUID key) {
        return jdbc.sql("select actor_id, key, request_hash, source, order_id from idempotency_record where actor_id = :a and key = :k")
                .param("a", actorId).param("k", key)
                .query((rs, n) -> new IdempotencyRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class)))
                .optional();
    }

    public Optional<UUID> findAnyKey(UUID key) {
        return jdbc.sql("select order_id from idempotency_record where key = :k order by created_at limit 1")
                .param("k", key).query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    /** Any actor: section 4.4 condition 5, the UUID has not already created an order. */
    public boolean keyUsedByAnyone(UUID key) {
        return jdbc.sql("select exists (select 1 from idempotency_record where key = :k)")
                .param("k", key).query(Boolean.class).single();
    }

    public void insertKey(UUID actorId, UUID key, String requestHash, String source, UUID orderId) {
        jdbc.sql("""
                        insert into idempotency_record (id, actor_id, key, request_hash, source, order_id, created_at)
                        values (:id, :a, :k, :h, :s, :o, :at)""")
                .param("id", UUID.randomUUID()).param("a", actorId).param("k", key).param("h", requestHash)
                .param("s", source).param("o", orderId).param("at", time.now())
                .update();
    }

    /** FOR SHARE: a concurrent shift close waits for this order, or this order sees the shift closed. */
    public OpenShift requireOpenShift() {
        return jdbc.sql("select id, business_date from shift where status = 'OPEN' for share")
                .query((rs, n) -> new OpenShift(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class)))
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.SHIFT_CLOSED, "No shift is open. Ask a manager to open one."));
    }

    public UUID create(QuoteRow quote, UUID cashierId, UUID createdBy, OpenShift shift, String recordStatus,
                       PaymentMethod method, String externalReference, UUID attestedBy) {
        QuoteSnapshot snapshot = json.read(quote.snapshot(), QuoteSnapshot.class);
        OffsetDateTime now = time.now();
        UUID orderId = UUID.randomUUID();
        int humanNo = jdbc.sql("""
                        insert into human_no_counter (business_date, last_no) values (:d, 1)
                        on conflict (business_date) do update set last_no = human_no_counter.last_no + 1
                        returning last_no""")
                .param("d", shift.businessDate()).query(Integer.class).single();
        jdbc.sql("""
                        insert into orders (id, quote_id, cashier_id, created_by, shift_id, business_date, human_no,
                                            record_status, total, placed_at)
                        values (:id, :quote, :cashier, :createdBy, :shift, :businessDate, :humanNo, :status, :total, :at)""")
                .param("id", orderId).param("quote", quote.id()).param("cashier", cashierId).param("createdBy", createdBy)
                .param("shift", shift.id()).param("businessDate", shift.businessDate()).param("humanNo", humanNo)
                .param("status", recordStatus).param("total", quote.total()).param("at", now)
                .update();
        for (QuoteSnapshot.Line line : snapshot.lines()) {
            UUID lineId = UUID.randomUUID();
            jdbc.sql("""
                            insert into order_line (id, order_id, line_no, menu_item_id, item_title, unit_base_price,
                                                    unit_price, quantity, line_total)
                            values (:id, :order, :no, :item, :title, :base, :unit, :qty, :total)""")
                    .param("id", lineId).param("order", orderId).param("no", line.lineNo()).param("item", line.menuItemId())
                    .param("title", line.title()).param("base", line.unitBasePrice()).param("unit", line.unitPrice())
                    .param("qty", line.quantity()).param("total", line.lineTotal())
                    .update();
            for (QuoteSnapshot.Modifier m : line.modifiers()) {
                jdbc.sql("""
                                insert into order_line_modifier (id, order_line_id, modifier_option_id, group_title,
                                                                 option_title, price_delta)
                                values (:id, :line, :option, :group, :title, :delta)""")
                        .param("id", UUID.randomUUID()).param("line", lineId).param("option", m.optionId())
                        .param("group", m.groupTitle()).param("title", m.title()).param("delta", m.priceDelta())
                        .update();
            }
        }
        jdbc.sql("""
                        insert into payment_record (id, order_id, method, amount, external_reference, attested_by, created_at)
                        values (:id, :order, :method, :amount, :ref, :by, :at)""")
                .param("id", UUID.randomUUID()).param("order", orderId).param("method", method.name())
                .param("amount", quote.total()).param("ref", externalReference).param("by", attestedBy).param("at", now)
                .update();
        return orderId;
    }

    public static String normalizeReference(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 64) {
            throw ApiException.invalid("externalReference", "externalReference has at most 64 characters.");
        }
        return trimmed;
    }

    /** Hash of the normalized submit body; the same key with another body is IDEMPOTENCY_KEY_REUSED. */
    public static String requestHash(UUID quoteId, PaymentMethod method, String externalReference) {
        String canonical = quoteId + "|" + method.name() + "|" + (externalReference == null ? "" : externalReference) + "|true";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
