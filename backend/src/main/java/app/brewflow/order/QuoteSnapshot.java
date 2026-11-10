package app.brewflow.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Immutable price snapshot stored in order_quote.snapshot (jsonb). Orders copy it; they never re-price. */
public record QuoteSnapshot(String currency, List<Line> lines, BigDecimal total) {

    public record Line(int lineNo, UUID menuItemId, String title, BigDecimal unitBasePrice, int quantity,
                       BigDecimal unitPrice, BigDecimal lineTotal, List<Modifier> modifiers) {
    }

    public record Modifier(UUID optionId, UUID groupId, String groupTitle, String title, BigDecimal priceDelta) {
    }
}
