package app.brewflow.shift;

import app.brewflow.audit.AuditService;
import app.brewflow.common.ApiException;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.Money;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.shift.ReportService.Report;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShiftService {

    public record Shift(UUID id, LocalDate businessDate, String status, UUID openedBy, OffsetDateTime openedAt,
                        UUID closedBy, OffsetDateTime closedAt, String countedCash) {
    }

    public record Closed(Shift shift, Report report) {
    }

    private static final String COLUMNS =
            "id, business_date, status, opened_by, opened_at, closed_by, closed_at, counted_cash";

    private final JdbcClient jdbc;
    private final ReportService reports;
    private final AuditService audit;
    private final TimeSource time;

    public ShiftService(JdbcClient jdbc, ReportService reports, AuditService audit, TimeSource time) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.audit = audit;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public List<Shift> recent() {
        return jdbc.sql("select " + COLUMNS + " from shift order by opened_at desc limit 30").query(this::map).list();
    }

    @Transactional
    public Shift open(UUID adminId) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("insert into shift (id, business_date, status, opened_by, opened_at) values (:id, :d, 'OPEN', :by, :at)")
                    .param("id", id).param("d", time.today()).param("by", adminId).param("at", time.now())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "A shift is already open.");
        }
        audit.record(adminId, "SHIFT_OPENED", "shift", id, Map.of());
        return find(id);
    }

    /** Close once. Returns the section 5 totals for the shift with the signed variance. */
    @Transactional
    public Closed close(UUID adminId, UUID shiftId, BigDecimal countedCash) {
        BigDecimal counted = Money.requireNonNegative("countedCash", countedCash);
        String status = jdbc.sql("select status from shift where id = :id for update")
                .param("id", shiftId).query(String.class).optional().orElseThrow(ApiException::notFound);
        if (!"OPEN".equals(status)) {
            throw new ApiException(ErrorCode.INVALID_TRANSITION, "This shift is already closed.");
        }
        jdbc.sql("update shift set status = 'CLOSED', closed_by = :by, closed_at = :at, counted_cash = :cash where id = :id")
                .param("id", shiftId).param("by", adminId).param("at", time.now()).param("cash", counted)
                .update();
        Shift shift = find(shiftId);
        Report report = reports.report(shift.businessDate(), shiftId, counted);
        audit.record(adminId, "SHIFT_CLOSED", "shift", shiftId,
                Map.of("countedCash", report.countedCash(), "cashTotal", report.cashTotal(), "variance", report.variance()));
        return new Closed(shift, report);
    }

    private Shift find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from shift where id = :id").param("id", id).query(this::map).single();
    }

    private Shift map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Shift(rs.getObject("id", UUID.class), rs.getObject("business_date", LocalDate.class),
                rs.getString("status"), rs.getObject("opened_by", UUID.class),
                rs.getObject("opened_at", OffsetDateTime.class), rs.getObject("closed_by", UUID.class),
                rs.getObject("closed_at", OffsetDateTime.class), Money.format(rs.getBigDecimal("counted_cash")));
    }
}
