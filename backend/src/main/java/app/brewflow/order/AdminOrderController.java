package app.brewflow.order;

import app.brewflow.common.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Day list for the admin screens (find an order to void). Not in the v3.2 route table; added with the admin UI. */
@RestController
@Tag(name = "Admin: orders", description = "Orders of one business date for the admin screens")
public class AdminOrderController {

    /** voidState: CONFIRMED, PENDING (unconfirmed) or null. */
    public record OrderSummary(UUID id, int humanNo, OffsetDateTime placedAt, String recordStatus, String prepStatus,
                               String total, String paymentMethod, String cashierName, String voidState) {
    }

    private final JdbcClient jdbc;

    public AdminOrderController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "Orders of a business date, newest first, with their void state")
    @GetMapping("/api/v1/admin/orders")
    @Transactional(readOnly = true)
    public List<OrderSummary> list(@RequestParam LocalDate businessDate) {
        return jdbc.sql("""
                        select o.id, o.human_no, o.placed_at, o.record_status, o.prep_status, o.total, p.method,
                               s.display_name,
                               case
                                   when exists (select 1 from order_correction c
                                                where c.order_id = o.id and c.external_action_state = 'CONFIRMED') then 'CONFIRMED'
                                   when exists (select 1 from order_correction c
                                                where c.order_id = o.id and c.external_action_state = 'PENDING') then 'PENDING'
                               end as void_state
                        from orders o
                                 join payment_record p on p.order_id = o.id
                                 join staff_user s on s.id = o.cashier_id
                        where o.business_date = :d
                        order by o.human_no desc""")
                .param("d", businessDate)
                .query((rs, n) -> new OrderSummary(rs.getObject(1, UUID.class), rs.getInt(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getString(4), rs.getString(5),
                        Money.format(rs.getBigDecimal(6)), rs.getString(7), rs.getString(8), rs.getString(9)))
                .list();
    }
}
