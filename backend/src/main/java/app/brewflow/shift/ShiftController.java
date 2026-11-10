package app.brewflow.shift;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.brewflow.common.ApiException;
import app.brewflow.security.CurrentUser;
import app.brewflow.shift.ReportService.Report;
import app.brewflow.shift.ShiftService.Closed;
import app.brewflow.shift.ShiftService.Shift;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin: shifts and reports", description = "Shifts and the section 5 internal report")
public class ShiftController {

    public record CloseBody(BigDecimal countedCash) {
    }

    private final ShiftService shifts;
    private final ReportService reports;

    public ShiftController(ShiftService shifts, ReportService reports) {
        this.shifts = shifts;
        this.reports = reports;
    }

    @Operation(summary = "Recent shifts")
    @GetMapping("/api/v1/admin/shifts")
    List<Shift> recent() {
        return shifts.recent();
    }

    @Operation(summary = "Open a shift; 409 if one is already open")
    @PostMapping("/api/v1/admin/shifts/open")
    @ResponseStatus(HttpStatus.CREATED)
    Shift open() {
        return shifts.open(CurrentUser.get().id());
    }

    @Operation(summary = "Close once with counted cash; returns totals and signed variance")
    @PostMapping("/api/v1/admin/shifts/{shiftId}/close")
    Closed close(@PathVariable UUID shiftId, @RequestBody CloseBody body) {
        return shifts.close(CurrentUser.get().id(), shiftId, body.countedCash());
    }

    /** Section 5 totals for one business date, or for one shift when shiftId is given. */
    @Operation(summary = "Section 5 totals for a business date, or for one shift with shiftId")
    @GetMapping("/api/v1/admin/reports/daily")
    Report daily(@RequestParam(required = false) LocalDate businessDate, @RequestParam(required = false) UUID shiftId) {
        if (shiftId != null) {
            return reports.shift(shiftId);
        }
        if (businessDate == null) {
            throw ApiException.invalid("businessDate", "businessDate or shiftId is required.");
        }
        return reports.daily(businessDate);
    }
}
