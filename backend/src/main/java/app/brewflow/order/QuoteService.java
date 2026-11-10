package app.brewflow.order;

import app.brewflow.common.ApiException;
import app.brewflow.common.BrewFlowProperties;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.Json;
import app.brewflow.common.Money;
import app.brewflow.common.TimeConfig.TimeSource;
import app.brewflow.menu.MenuService;
import app.brewflow.menu.MenuService.ActiveOption;
import app.brewflow.menu.MenuService.AttachedGroup;
import app.brewflow.menu.MenuService.QuotableItem;
import app.brewflow.order.QuoteRepository.QuoteRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Section 4.1: prices come from the database; client amounts are ignored. Line total is
 * quantity * (unit base + sum of option deltas), HALF_UP to two decimals. No tax field.
 */
@Service
public class QuoteService {

    public static final int MAX_LINES = 50;
    public static final int MAX_QUANTITY = 99;

    public record LineRequest(UUID menuItemId, Integer quantity, List<UUID> optionIds) {
    }

    private final MenuService menu;
    private final QuoteRepository quotes;
    private final Json json;
    private final TimeSource time;
    private final BrewFlowProperties props;

    public QuoteService(MenuService menu, QuoteRepository quotes, Json json, TimeSource time, BrewFlowProperties props) {
        this.menu = menu;
        this.quotes = quotes;
        this.json = json;
        this.time = time;
        this.props = props;
    }

    @Transactional
    public QuoteRow create(UUID cashierId, List<LineRequest> request) {
        if (request == null || request.isEmpty() || request.size() > MAX_LINES) {
            throw ApiException.invalid("lines", "A quote needs 1 to " + MAX_LINES + " lines.");
        }
        List<QuoteSnapshot.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (int i = 0; i < request.size(); i++) {
            QuoteSnapshot.Line line = price(i, request.get(i));
            lines.add(line);
            total = total.add(line.lineTotal());
        }
        total = Money.round(total);
        if (total.signum() < 0 || total.compareTo(Money.MAX) > 0) {
            throw ApiException.invalid("lines", "The quote total is out of range.");
        }
        OffsetDateTime now = time.now();
        UUID id = UUID.randomUUID();
        QuoteRow row = new QuoteRow(id, cashierId, quoteRef(id),
                json.write(new QuoteSnapshot(props.currency(), lines, total)), total, time.today(), now,
                now.plus(props.quoteTtl()));
        quotes.insert(row);
        return row;
    }

    /** quoteRef: the last six characters of quoteId, upper case. */
    public static String quoteRef(UUID id) {
        String s = id.toString();
        return s.substring(s.length() - 6).toUpperCase(Locale.ROOT);
    }

    private QuoteSnapshot.Line price(int index, LineRequest req) {
        if (req == null || req.menuItemId() == null) {
            throw new ApiException(ErrorCode.INVALID_INPUT, "Line " + index + " has no menuItemId.", Map.of("line", index));
        }
        if (req.quantity() == null || req.quantity() < 1 || req.quantity() > MAX_QUANTITY) {
            throw new ApiException(ErrorCode.INVALID_QUANTITY, "Quantity must be 1 to " + MAX_QUANTITY + ".",
                    Map.of("line", index));
        }
        QuotableItem item = menu.quotableItem(req.menuItemId()).orElseThrow(() -> new ApiException(
                ErrorCode.INVALID_INPUT, "This item is unavailable.",
                Map.of("line", index, "menuItemId", req.menuItemId(), "reason", "ITEM_UNAVAILABLE")));

        List<UUID> optionIds = req.optionIds() == null ? List.of() : req.optionIds();
        Set<UUID> distinct = new LinkedHashSet<>(optionIds);
        if (distinct.contains(null) || distinct.size() != optionIds.size()) {
            throw violation(index, "An option is listed twice or is empty.");
        }
        List<AttachedGroup> groups = menu.activeGroupsOf(item.id());
        Map<UUID, AttachedGroup> groupsById = new HashMap<>();
        groups.forEach(g -> groupsById.put(g.id(), g));
        Map<UUID, ActiveOption> options = menu.activeOptions(distinct);

        Map<UUID, Integer> selectedPerGroup = new HashMap<>();
        List<QuoteSnapshot.Modifier> modifiers = new ArrayList<>();
        BigDecimal unit = item.basePrice();
        for (UUID optionId : distinct) {
            ActiveOption option = options.get(optionId);
            if (option == null || !groupsById.containsKey(option.groupId())) {
                throw violation(index, "An option is unavailable or does not belong to this item.");
            }
            if (option.priceDelta().signum() < 0) {
                throw violation(index, "Negative modifier prices are not allowed in the pilot.");
            }
            selectedPerGroup.merge(option.groupId(), 1, Integer::sum);
            AttachedGroup group = groupsById.get(option.groupId());
            modifiers.add(new QuoteSnapshot.Modifier(option.id(), group.id(), group.title(), option.title(),
                    Money.round(option.priceDelta())));
            unit = unit.add(option.priceDelta());
        }
        for (AttachedGroup g : groups) {
            int n = selectedPerGroup.getOrDefault(g.id(), 0);
            if (n < g.minSelected() || n > g.maxSelected()) {
                throw violation(index, "Group '" + g.title() + "' needs " + g.minSelected() + " to " + g.maxSelected()
                        + " choices.");
            }
        }
        BigDecimal unitPrice = Money.round(unit);
        BigDecimal lineTotal = Money.round(unit.multiply(BigDecimal.valueOf(req.quantity())));
        if (lineTotal.signum() < 0) {
            throw violation(index, "Negative lines are not allowed in the pilot.");
        }
        return new QuoteSnapshot.Line(index + 1, item.id(), item.title(), Money.round(item.basePrice()), req.quantity(),
                unitPrice, lineTotal, List.copyOf(modifiers));
    }

    private static ApiException violation(int index, String message) {
        return new ApiException(ErrorCode.MODIFIER_RULE_VIOLATION, message, Map.of("line", index));
    }
}
