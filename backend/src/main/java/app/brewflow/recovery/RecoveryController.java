package app.brewflow.recovery;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.brewflow.common.ApiErrorWriter;
import app.brewflow.common.ApiException;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.Money;
import app.brewflow.order.QuoteRepository.ExpiredUnconsumed;
import app.brewflow.recovery.RecoveryService.Mismatch;
import app.brewflow.recovery.RecoveryService.Outcome;
import app.brewflow.recovery.RecoveryService.RecoverRequest;
import app.brewflow.recovery.RecoveryService.Recovered;
import app.brewflow.security.CurrentUser;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin: quote recovery", description = "Orphan-receipt recovery (section 4.4)")
public class RecoveryController {

    public record RecoverBody(String attestedAmount, String paymentMethod, String externalReference, String reason,
                              UUID idempotencyKey) {
    }

    private final RecoveryService recovery;

    public RecoveryController(RecoveryService recovery) {
        this.recovery = recovery;
    }

    @Operation(summary = "Expired quotes with no order for a business date (status=EXPIRED_UNCONSUMED)")
    @GetMapping("/api/v1/admin/quotes")
    List<Map<String, Object>> expired(@RequestParam String status, @RequestParam LocalDate businessDate) {
        if (!"EXPIRED_UNCONSUMED".equals(status)) {
            throw ApiException.invalid("status", "status must be EXPIRED_UNCONSUMED.");
        }
        return recovery.expiredUnconsumed(businessDate).stream().map(RecoveryController::row).toList();
    }

    /** 201 exception order, 200 if the quote was already consumed, 409 AMOUNT_MISMATCH with a pending exception. */
    @Operation(summary = "Recover from the frozen quote: 201 EXCEPTION order, 200 if consumed, 409 AMOUNT_MISMATCH")
    @PostMapping("/api/v1/admin/quotes/{quoteId}/recover")
    ResponseEntity<Map<String, Object>> recover(@PathVariable UUID quoteId, @RequestBody RecoverBody body) {
        Outcome outcome = recovery.recover(CurrentUser.get().id(), quoteId, new RecoverRequest(
                body.attestedAmount(), body.paymentMethod(), body.externalReference(), body.reason(), body.idempotencyKey()));
        return switch (outcome) {
            case Recovered r -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("order", r.order());
                result.put("instruction", "DO_NOT_ISSUE_ANOTHER_FISCAL_RECEIPT");
                result.put("message", RecoveryService.NO_SECOND_RECEIPT);
                yield ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
            }
            case Mismatch m -> ResponseEntity.status(ErrorCode.AMOUNT_MISMATCH.status()).body(ApiErrorWriter.envelope(
                    ErrorCode.AMOUNT_MISMATCH,
                    "The attested amount differs from the quote. No order was created. Do not issue a second receipt.",
                    Map.of("exceptionId", m.exceptionId(), "snapshotTotal", m.snapshotTotal(),
                            "attestedAmount", m.attestedAmount())));
        };
    }

    @Operation(summary = "Close a pending amount-mismatch exception without an order")
    @PostMapping("/api/v1/admin/quotes/exceptions/{exceptionId}/resolve-outside")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolveOutside(@PathVariable UUID exceptionId) {
        recovery.resolveOutside(CurrentUser.get().id(), exceptionId);
    }

    private static Map<String, Object> row(ExpiredUnconsumed q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("quoteId", q.quoteId());
        m.put("quoteRef", q.quoteRef());
        m.put("cashierId", q.cashierId());
        m.put("cashierName", q.cashierName());
        m.put("total", Money.format(q.total()));
        m.put("expiresAt", q.expiresAt());
        m.put("pendingExceptionIds", q.pendingExceptionIds());
        return m;
    }
}
