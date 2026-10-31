package mx.surtido.orders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import mx.surtido.contracts.OrderEvents.RequestedLine;

@RestController
public class OrderController {
    private final OrderSaga saga;
    private final JdbcClient db;

    public OrderController(OrderSaga saga, JdbcClient db) {
        this.saga = saga;
        this.db = db;
    }

    public record StoreView(UUID id, String name, String ownerName, String email, String address, String city) {}

    @GetMapping("/stores")
    public List<StoreView> stores() {
        return db.sql("SELECT id, name, owner_name, email, address, city FROM stores ORDER BY name")
                .query((rs, i) -> new StoreView(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("owner_name"),
                        rs.getString("email"), rs.getString("address"), rs.getString("city")))
                .list();
    }

    public record PlaceOrder(
            @NotNull UUID storeId, @NotNull OrderSaga.PaymentMethod paymentMethod, @NotEmpty List<@Valid RequestedLine> lines) {}

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> place(@Valid @RequestBody PlaceOrder body) {
        return Map.of("id", saga.place(body.storeId(), body.paymentMethod(), body.lines()));
    }

    public record Cancel(@NotNull UUID storeId) {}

    @PostMapping("/orders/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id, @Valid @RequestBody Cancel body) {
        saga.cancel(id, body.storeId());
    }

    public record Line(UUID productId, String productName, int quantity, Long unitPriceCents) {}

    public record Step(String step, String detail, Instant occurredAt) {}

    public record OrderView(
            UUID id, String status, String paymentMethod, Long totalCents, String failureReason,
            Instant createdAt, Instant expiresAt, StoreView store, List<Line> lines, List<Step> timeline) {}

    @GetMapping("/orders/{id}")
    public OrderView get(@PathVariable UUID id) {
        record Row(UUID id, UUID storeId, String status, String method, Long total, String reason, Instant createdAt, Instant expiresAt) {}
        Row row = db.sql("""
                SELECT id, store_id, status, payment_method, total_cents, failure_reason, created_at, expires_at
                FROM orders WHERE id = ?
                """)
                .param(id)
                .query((rs, i) -> {
                    long total = rs.getLong("total_cents");
                    Long totalOrNull = rs.wasNull() ? null : total;
                    var expires = rs.getTimestamp("expires_at");
                    return new Row(
                            rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class), rs.getString("status"),
                            rs.getString("payment_method"), totalOrNull, rs.getString("failure_reason"),
                            rs.getTimestamp("created_at").toInstant(), expires == null ? null : expires.toInstant());
                })
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        StoreView store = stores().stream().filter(s -> s.id().equals(row.storeId())).findFirst().orElseThrow();
        List<Line> lines = db.sql("""
                SELECT product_id, product_name, quantity, unit_price_cents FROM order_lines
                WHERE order_id = ? ORDER BY product_name NULLS LAST, product_id
                """)
                .param(id)
                .query((rs, i) -> {
                    long price = rs.getLong("unit_price_cents");
                    return new Line(rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                            rs.getInt("quantity"), rs.wasNull() ? null : price);
                })
                .list();
        List<Step> timeline = db.sql("SELECT step, detail, occurred_at FROM order_timeline WHERE order_id = ? ORDER BY id")
                .param(id)
                .query((rs, i) -> new Step(rs.getString("step"), rs.getString("detail"), rs.getTimestamp("occurred_at").toInstant()))
                .list();
        return new OrderView(row.id(), row.status(), row.method(), row.total(), row.reason(), row.createdAt(),
                row.expiresAt(), store, lines, timeline);
    }

    public record OrderSummary(
            UUID id, String storeName, String status, String paymentMethod, Long totalCents, int lines, Instant createdAt) {}

    /** Recent orders, for one store or for the warehouse. */
    @GetMapping("/orders")
    public List<OrderSummary> list(
            @RequestParam(required = false) UUID storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return db.sql("""
                SELECT o.id, s.name AS store_name, o.status, o.payment_method, o.total_cents, o.created_at,
                  (SELECT count(*) FROM order_lines l WHERE l.order_id = o.id) AS lines
                FROM orders o JOIN stores s ON s.id = o.store_id
                WHERE (CAST(:storeId AS uuid) IS NULL OR o.store_id = :storeId)
                  AND (CAST(:status AS text) IS NULL OR o.status = :status)
                ORDER BY o.created_at DESC LIMIT :limit
                """)
                .param("storeId", storeId)
                .param("status", status)
                .param("limit", Math.min(Math.max(limit, 1), 200))
                .query((rs, i) -> {
                    long total = rs.getLong("total_cents");
                    return new OrderSummary(rs.getObject("id", UUID.class), rs.getString("store_name"), rs.getString("status"),
                            rs.getString("payment_method"), rs.wasNull() ? null : total, rs.getInt("lines"),
                            rs.getTimestamp("created_at").toInstant());
                })
                .list();
    }

    public record StatusCount(String status, int orders, long totalCents) {}

    /** Orders per status today, for the warehouse dashboard. */
    @GetMapping("/orders/stats")
    public List<StatusCount> stats() {
        return db.sql("""
                SELECT status, count(*) AS n, coalesce(sum(total_cents), 0) AS total FROM orders
                WHERE created_at > now() - interval '24 hours' GROUP BY status
                """)
                .query((rs, i) -> new StatusCount(rs.getString("status"), rs.getInt("n"), rs.getLong("total")))
                .list();
    }
}
