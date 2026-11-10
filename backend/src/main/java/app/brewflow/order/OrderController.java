package app.brewflow.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.brewflow.common.ApiException;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.Json;
import app.brewflow.common.Money;
import app.brewflow.order.OrderService.SubmitRequest;
import app.brewflow.order.OrderService.SubmitResult;
import app.brewflow.order.OrderViews.Kitchen;
import app.brewflow.order.OrderViews.Line;
import app.brewflow.order.OrderViews.Modifier;
import app.brewflow.order.OrderViews.Order;
import app.brewflow.order.OrderViews.Quote;
import app.brewflow.order.QuoteRepository.QuoteRow;
import app.brewflow.order.QuoteService.LineRequest;
import app.brewflow.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Orders", description = "Quotes, idempotent submit and order reads (section 4)")
@RequestMapping("/api/v1/orders")
public class OrderController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    public record QuoteRequest(@NotNull @Size(min = 1, max = QuoteService.MAX_LINES) List<LineRequest> lines) {
    }

    public record SubmitBody(UUID quoteId, String paymentMethod, String externalReference,
                             Boolean confirmedExternalAction) {
    }

    private final QuoteService quotes;
    private final OrderService orders;
    private final OrderReadService reads;
    private final Json json;

    public OrderController(QuoteService quotes, OrderService orders, OrderReadService reads, Json json) {
        this.quotes = quotes;
        this.orders = orders;
        this.reads = reads;
        this.json = json;
    }

    @Operation(summary = "Price a cart on the server: 201 quote with quoteRef, valid 10 minutes")
    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    Quote quote(@Valid @RequestBody QuoteRequest body) {
        QuoteRow row = quotes.create(CurrentUser.get().id(), body.lines());
        QuoteSnapshot snapshot = json.read(row.snapshot(), QuoteSnapshot.class);
        List<Line> lines = snapshot.lines().stream()
                .map(l -> new Line(l.lineNo(), l.menuItemId(), l.title(), l.quantity(), Money.format(l.unitBasePrice()),
                        Money.format(l.unitPrice()), Money.format(l.lineTotal()), l.modifiers().stream()
                        .map(m -> new Modifier(m.optionId(), m.groupTitle(), m.title(), Money.format(m.priceDelta())))
                        .toList()))
                .toList();
        return new Quote(row.id(), row.quoteRef(), snapshot.currency(), lines, Money.format(row.total()),
                row.createdAt(), row.expiresAt(), "Provisional until the order is recorded. No tax line in the pilot.");
    }

    @Operation(summary = "Record an order from a quote (Idempotency-Key required)", responses = {
            @ApiResponse(responseCode = "201", description = "Order created",
                    content = @Content(schema = @Schema(implementation = Order.class))),
            @ApiResponse(responseCode = "200", description = "Same key and same body: the original order",
                    content = @Content(schema = @Schema(implementation = Order.class)))})
    @PostMapping
    ResponseEntity<Order> submit(@Parameter(description = "UUID kept by the POS across timeout, refresh and retry",
                                         required = true, example = "3f1c2a9e-5b7d-4e21-9c0a-8d6f4b2e1a37")
                                 @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key,
                                 @RequestBody SubmitBody body) {
        UUID idempotencyKey = parseKey(key);
        SubmitResult result = orders.submit(CurrentUser.get(), idempotencyKey, new SubmitRequest(
                body.quoteId(), body.paymentMethod(), body.externalReference(), body.confirmedExternalAction()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }

    @Operation(summary = "Recover an order by Idempotency-Key after a lost response")
    @GetMapping("/by-key/{key}")
    Order byKey(@PathVariable String key) {
        UUID parsed;
        try {
            parsed = UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound();
        }
        return orders.byKey(CurrentUser.get(), parsed);
    }

    @Operation(summary = "Full order with lines, payment and corrections (creator or admin)")
    @GetMapping("/{id}")
    Order get(@PathVariable UUID id) {
        return reads.visibleOrder(CurrentUser.get(), id);
    }

    @Operation(summary = "Kitchen projection without tender fields (barista, admin)")
    @GetMapping("/{id}/kitchen")
    Kitchen kitchen(@PathVariable UUID id) {
        return reads.kitchen(id).orElseThrow(ApiException::notFound);
    }

    private static UUID parseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_INPUT,
                    "Header Idempotency-Key is required.", Map.of("header", IDEMPOTENCY_HEADER));
        }
        try {
            UUID key = UUID.fromString(raw.trim());
            if (!key.toString().equalsIgnoreCase(raw.trim())) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_INPUT,
                    "Idempotency-Key must be a UUID.", Map.of("header", IDEMPOTENCY_HEADER));
        }
    }
}
