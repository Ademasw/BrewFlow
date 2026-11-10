package app.brewflow.shift;

import app.brewflow.common.ApiException;
import app.brewflow.common.BrewFlowProperties;
import app.brewflow.common.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Section 5 report formula. Internal record only, not a fiscal ledger. A confirmed VOID_NOTE removes the full
 * payment amount; a pending one does not. TRAINING payments are excluded from all totals.
 */
@Service
public class ReportService {

    public record TopItem(UUID menuItemId, String title, long quantity) {
    }

    public record Report(LocalDate businessDate, UUID shiftId, String currency, String cashTotal,
                         String externalCardTotal, String externalQrTotal, String recordedTenderTotal,
                         long pendingExternalCorrections, String countedCash, String variance, long orderCount,
                         long trainingOrderCount, long exceptionOrderCount, List<TopItem> topItems, String label) {
    }

    private static final String NOT_CONFIRMED_VOID = """
            not exists (select 1 from order_correction v where v.order_id = o.id and v.external_action_state = 'CONFIRMED')""";

    private final JdbcClient jdbc;
    private final BrewFlowProperties props;

    public ReportService(JdbcClient jdbc, BrewFlowProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** One shift (shiftId) or one business date across that site's shifts (shiftId null). */
    @Transactional(readOnly = true)
    public Report report(LocalDate businessDate, UUID shiftId, BigDecimal countedCash) {
        String scope = shiftId == null ? "o.business_date = :date" : "o.shift_id = :shift";
        Map<String, Object> params = new LinkedHashMap<>();
        if (shiftId == null) {
            params.put("date", businessDate);
        } else {
            params.put("shift", shiftId);
        }

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        jdbc.sql("select p.method, sum(p.amount) from payment_record p join orders o on o.id = p.order_id where "
                        + scope + " and p.method <> 'TRAINING' and o.record_status <> 'TRAINING' and " + NOT_CONFIRMED_VOID
                        + " group by p.method")
                .params(params)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getBigDecimal(2)))
                .list()
                .forEach(e -> totals.put(e.getKey(), e.getValue()));
        BigDecimal cash = Money.round(totals.getOrDefault("CASH", BigDecimal.ZERO));
        BigDecimal card = Money.round(totals.getOrDefault("EXTERNAL_CARD", BigDecimal.ZERO));
        BigDecimal qr = Money.round(totals.getOrDefault("EXTERNAL_QR", BigDecimal.ZERO));

        long pending = jdbc.sql("select count(*) from order_correction c join orders o on o.id = c.order_id where " + scope
                        + " and c.external_action_state = 'PENDING'"
                        + " and not exists (select 1 from order_correction k where k.confirms_correction_id = c.id)")
                .params(params).query(Long.class).single();

        record Counts(long all, long training, long exception) {
        }
        Counts counts = jdbc.sql("select count(*), count(*) filter (where o.record_status = 'TRAINING'),"
                        + " count(*) filter (where o.record_status = 'EXCEPTION') from orders o where " + scope)
                .params(params).query((rs, n) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3))).single();

        List<TopItem> top = jdbc.sql("select l.menu_item_id, min(l.item_title), sum(l.quantity) as qty"
                        + " from order_line l join orders o on o.id = l.order_id where " + scope + " and " + NOT_CONFIRMED_VOID
                        + " group by l.menu_item_id order by qty desc, min(l.item_title) limit 10")
                .params(params)
                .query((rs, n) -> new TopItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3)))
                .list();

        BigDecimal counted = countedCash == null ? null : Money.round(countedCash);
        BigDecimal variance = counted == null ? null : counted.subtract(cash);
        return new Report(businessDate, shiftId, props.currency(), Money.format(cash), Money.format(card),
                Money.format(qr), Money.format(cash.add(card).add(qr)), pending, Money.format(counted),
                Money.format(variance), counts.all(), counts.training(), counts.exception(), top,
                "Internal record, not a fiscal reconciliation.");
    }

    /** Daily counted cash is the sum over that date's closed shifts; null while none is closed. */
    @Transactional(readOnly = true)
    public Report daily(LocalDate businessDate) {
        BigDecimal counted = jdbc.sql("select sum(counted_cash) from shift where business_date = :d and status = 'CLOSED'")
                .param("d", businessDate).query((rs, n) -> rs.getBigDecimal(1)).list().getFirst();
        return report(businessDate, null, counted);
    }

    /** One shift; counted cash and variance appear once the shift is closed. */
    @Transactional(readOnly = true)
    public Report shift(UUID shiftId) {
        record S(LocalDate date, BigDecimal counted) {
        }
        S s = jdbc.sql("select business_date, counted_cash from shift where id = :id")
                .param("id", shiftId)
                .query((rs, n) -> new S(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2)))
                .optional().orElseThrow(ApiException::notFound);
        return report(s.date(), shiftId, s.counted());
    }
}
