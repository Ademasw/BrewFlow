package app.brewflow.order;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuoteRepository {

    public record QuoteRow(UUID id, UUID cashierId, String quoteRef, String snapshot, BigDecimal total,
                           LocalDate businessDate, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
    }

    public record ExpiredUnconsumed(UUID quoteId, String quoteRef, UUID cashierId, String cashierName,
                                    BigDecimal total, OffsetDateTime expiresAt, List<UUID> pendingExceptionIds) {
    }

    private static final String COLUMNS =
            "id, cashier_id, quote_ref, snapshot::text as snapshot, total, business_date, created_at, expires_at";

    private final JdbcClient jdbc;

    public QuoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(QuoteRow q) {
        jdbc.sql("""
                        insert into order_quote (id, cashier_id, quote_ref, snapshot, total, business_date, created_at, expires_at)
                        values (:id, :cashier, :ref, cast(:snapshot as jsonb), :total, :businessDate, :createdAt, :expiresAt)
                        """)
                .param("id", q.id()).param("cashier", q.cashierId()).param("ref", q.quoteRef())
                .param("snapshot", q.snapshot()).param("total", q.total()).param("businessDate", q.businessDate())
                .param("createdAt", q.createdAt()).param("expiresAt", q.expiresAt())
                .update();
    }

    public Optional<QuoteRow> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from order_quote where id = :id")
                .param("id", id).query(QuoteRepository::map).optional();
    }

    /** Row lock that serializes every submit and recovery of one quote. */
    public Optional<QuoteRow> lock(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from order_quote where id = :id for update")
                .param("id", id).query(QuoteRepository::map).optional();
    }

    public Optional<UUID> consumingOrder(UUID quoteId) {
        return jdbc.sql("select id from orders where quote_id = :id")
                .param("id", quoteId).query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    /** Section 4.4 lookup: expired quotes of that business date that no order consumed. */
    public List<ExpiredUnconsumed> expiredUnconsumed(LocalDate businessDate, OffsetDateTime now) {
        return jdbc.sql("""
                        select q.id, q.quote_ref, q.cashier_id, s.display_name, q.total, q.expires_at,
                               coalesce(array_agg(e.id order by e.created_at) filter (where e.id is not null), '{}') as exceptions
                        from order_quote q
                                 join staff_user s on s.id = q.cashier_id
                                 left join external_action_exception e
                                           on e.quote_id = q.id and e.state = 'PENDING' and e.resolved_order_id is null
                        where q.business_date = :businessDate
                          and q.expires_at <= :now
                          and not exists (select 1 from orders o where o.quote_id = q.id)
                        group by q.id, q.quote_ref, q.cashier_id, s.display_name, q.total, q.expires_at
                        order by q.expires_at, q.id
                        """)
                .param("businessDate", businessDate).param("now", now)
                .query((rs, n) -> new ExpiredUnconsumed(
                        rs.getObject("id", UUID.class),
                        rs.getString("quote_ref"),
                        rs.getObject("cashier_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getBigDecimal("total"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        List.of((UUID[]) rs.getArray("exceptions").getArray())))
                .list();
    }

    private static QuoteRow map(ResultSet rs, int n) throws SQLException {
        return new QuoteRow(
                rs.getObject("id", UUID.class),
                rs.getObject("cashier_id", UUID.class),
                rs.getString("quote_ref"),
                rs.getString("snapshot"),
                rs.getBigDecimal("total"),
                rs.getObject("business_date", LocalDate.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class));
    }
}
