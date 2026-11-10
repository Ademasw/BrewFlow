package app.brewflow.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class OrderViews {

    private OrderViews() {
    }

    public record Quote(UUID quoteId, String quoteRef, String currency, List<Line> lines, String total,
                        OffsetDateTime createdAt, OffsetDateTime expiresAt, String note) {
    }

    public record Order(UUID id, int humanNo, UUID quoteId, String quoteRef, UUID cashierId, UUID createdBy,
                        UUID shiftId, LocalDate businessDate, String recordStatus, String prepStatus, int version,
                        String total, String currency, OffsetDateTime placedAt, List<Line> lines, Payment payment,
                        List<Correction> corrections) {
    }

    public record Line(int lineNo, UUID menuItemId, String title, int quantity, String unitBasePrice,
                       String unitPrice, String lineTotal, List<Modifier> modifiers) {
    }

    public record Modifier(UUID optionId, String groupTitle, String title, String priceDelta) {
    }

    /** Recorded by staff, not verified: BrewFlow does not see the KKM or the card terminal. */
    public record Payment(String method, String amount, String externalReference, UUID attestedBy,
                          OffsetDateTime recordedAt, String verification) {
    }

    public record Correction(UUID id, String type, String externalActionState, UUID confirmsCorrectionId,
                             String reason, UUID createdBy, OffsetDateTime createdAt) {
    }

    /** Kitchen projection: no tender, amount or external reference. */
    public record Kitchen(UUID orderId, int humanNo, String prepStatus, int version, OffsetDateTime placedAt,
                          List<KitchenLine> lines) {
    }

    public record KitchenLine(int lineNo, String title, int quantity, List<KitchenModifier> modifiers) {
    }

    public record KitchenModifier(String groupTitle, String title) {
    }
}
