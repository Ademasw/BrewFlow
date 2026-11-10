package app.brewflow.order;

import app.brewflow.common.ApiException;
import app.brewflow.common.BrewFlowProperties;
import app.brewflow.common.Money;
import app.brewflow.order.OrderViews.Correction;
import app.brewflow.order.OrderViews.Kitchen;
import app.brewflow.order.OrderViews.KitchenLine;
import app.brewflow.order.OrderViews.KitchenModifier;
import app.brewflow.order.OrderViews.Line;
import app.brewflow.order.OrderViews.Modifier;
import app.brewflow.order.OrderViews.Order;
import app.brewflow.order.OrderViews.Payment;
import app.brewflow.security.StaffPrincipal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderReadService {

    public static final String VERIFICATION = "RECORDED_BY_STAFF_NOT_VERIFIED";

    private record Head(UUID id, int humanNo, UUID quoteId, String quoteRef, UUID cashierId, UUID createdBy,
                        UUID shiftId, LocalDate businessDate, String recordStatus, String prepStatus, int version,
                        String total, OffsetDateTime placedAt) {
    }

    private final JdbcClient jdbc;
    private final BrewFlowProperties props;

    public OrderReadService(JdbcClient jdbc, BrewFlowProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Full order for its creating cashier or an admin; anyone else gets 404 so existence does not leak. */
    @Transactional(readOnly = true)
    public Order visibleOrder(StaffPrincipal viewer, UUID orderId) {
        Order order = full(orderId).orElseThrow(ApiException::notFound);
        if (!viewer.isAdmin() && !order.cashierId().equals(viewer.id())) {
            throw ApiException.notFound();
        }
        return order;
    }

    @Transactional(readOnly = true)
    public Optional<Order> full(UUID orderId) {
        Optional<Head> head = jdbc.sql("""
                        select o.id, o.human_no, o.quote_id, q.quote_ref, o.cashier_id, o.created_by, o.shift_id,
                               o.business_date, o.record_status, o.prep_status, o.prep_version, o.total, o.placed_at
                        from orders o join order_quote q on q.id = o.quote_id where o.id = :id""")
                .param("id", orderId)
                .query((rs, n) -> new Head(rs.getObject(1, UUID.class), rs.getInt(2), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                        rs.getObject(7, UUID.class), rs.getObject(8, LocalDate.class), rs.getString(9),
                        rs.getString(10), rs.getInt(11), Money.format(rs.getBigDecimal(12)),
                        rs.getObject(13, OffsetDateTime.class)))
                .optional();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        Head h = head.get();
        Payment payment = jdbc.sql("""
                        select method, amount, external_reference, attested_by, created_at
                        from payment_record where order_id = :id""")
                .param("id", orderId)
                .query((rs, n) -> new Payment(rs.getString(1), Money.format(rs.getBigDecimal(2)), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getObject(5, OffsetDateTime.class), VERIFICATION))
                .single();
        List<Correction> corrections = jdbc.sql("""
                        select id, type, external_action_state, confirms_correction_id, reason, created_by, created_at
                        from order_correction where order_id = :id order by created_at, id""")
                .param("id", orderId)
                .query((rs, n) -> new Correction(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getObject(6, UUID.class),
                        rs.getObject(7, OffsetDateTime.class)))
                .list();
        List<Line> lines = lines(List.of(orderId)).getOrDefault(orderId, List.of());
        return Optional.of(new Order(h.id(), h.humanNo(), h.quoteId(), h.quoteRef(), h.cashierId(), h.createdBy(),
                h.shiftId(), h.businessDate(), h.recordStatus(), h.prepStatus(), h.version(), h.total(),
                props.currency(), h.placedAt(), lines, payment, corrections));
    }

    @Transactional(readOnly = true)
    public Optional<Kitchen> kitchen(UUID orderId) {
        return kitchenWhere("o.id = :id", Map.of("id", orderId)).stream().findFirst();
    }

    /** Open tickets: prep_status NEW, PREPARING or READY, oldest first. */
    @Transactional(readOnly = true)
    public List<Kitchen> openTickets() {
        return kitchenWhere("o.prep_status in ('NEW', 'PREPARING', 'READY')", Map.of());
    }

    private List<Kitchen> kitchenWhere(String condition, Map<String, ?> params) {
        record K(UUID id, int humanNo, String status, int version, OffsetDateTime placedAt) {
        }
        List<K> heads = jdbc.sql("select o.id, o.human_no, o.prep_status, o.prep_version, o.placed_at from orders o where "
                        + condition + " order by o.placed_at, o.id")
                .params(params)
                .query((rs, n) -> new K(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getInt(4),
                        rs.getObject(5, OffsetDateTime.class)))
                .list();
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Line>> lines = lines(heads.stream().map(K::id).toList());
        return heads.stream()
                .map(k -> new Kitchen(k.id(), k.humanNo(), k.status(), k.version(), k.placedAt(),
                        lines.getOrDefault(k.id(), List.of()).stream()
                                .map(l -> new KitchenLine(l.lineNo(), l.title(), l.quantity(), l.modifiers().stream()
                                        .map(m -> new KitchenModifier(m.groupTitle(), m.title())).toList()))
                                .toList()))
                .toList();
    }

    private Map<UUID, List<Line>> lines(List<UUID> orderIds) {
        record RawLine(UUID id, UUID orderId, int lineNo, UUID itemId, String title, int qty, String base, String unit,
                       String total) {
        }
        List<RawLine> raw = jdbc.sql("""
                        select id, order_id, line_no, menu_item_id, item_title, quantity, unit_base_price, unit_price, line_total
                        from order_line where order_id in (:ids) order by order_id, line_no""")
                .param("ids", orderIds)
                .query((rs, n) -> new RawLine(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getInt(6), Money.format(rs.getBigDecimal(7)),
                        Money.format(rs.getBigDecimal(8)), Money.format(rs.getBigDecimal(9))))
                .list();
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Modifier>> modifiers = new LinkedHashMap<>();
        jdbc.sql("""
                        select order_line_id, modifier_option_id, group_title, option_title, price_delta
                        from order_line_modifier where order_line_id in (:ids) order by group_title, option_title, id""")
                .param("ids", raw.stream().map(RawLine::id).toList())
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), new Modifier(rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), Money.format(rs.getBigDecimal(5)))))
                .list()
                .forEach(e -> modifiers.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return raw.stream().collect(Collectors.groupingBy(RawLine::orderId, LinkedHashMap::new,
                Collectors.mapping(l -> new Line(l.lineNo(), l.itemId(), l.title(), l.qty(), l.base(), l.unit(), l.total(),
                        List.copyOf(modifiers.getOrDefault(l.id(), List.of()))), Collectors.toList())));
    }
}
