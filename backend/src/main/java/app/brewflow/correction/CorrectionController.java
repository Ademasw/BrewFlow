package app.brewflow.correction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.brewflow.correction.CorrectionService.VoidRequest;
import app.brewflow.order.OrderViews.Order;
import app.brewflow.security.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Admin: voids", description = "Full-order VOID_NOTE corrections (section 4.6)")
public class CorrectionController {

    public record CorrectionBody(String type, String externalActionState, UUID confirmsCorrectionId, String reason) {
    }

    private final CorrectionService corrections;

    public CorrectionController(CorrectionService corrections) {
        this.corrections = corrections;
    }

    /** Returns the full order with its corrections. */
    @Operation(summary = "Append a VOID_NOTE: PENDING, CONFIRMED (optionally confirming a pending note) or NOT_APPLICABLE")
    @PostMapping("/api/v1/admin/orders/{id}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    Order append(@PathVariable UUID id, @RequestBody CorrectionBody body) {
        return corrections.append(CurrentUser.get().id(), id,
                new VoidRequest(body.type(), body.externalActionState(), body.confirmsCorrectionId(), body.reason()));
    }
}
