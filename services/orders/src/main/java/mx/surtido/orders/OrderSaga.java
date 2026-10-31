package mx.surtido.orders;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import mx.surtido.contracts.InventoryEvents.StockReleased;
import mx.surtido.contracts.InventoryEvents.StockRejected;
import mx.surtido.contracts.InventoryEvents.StockReserved;
import mx.surtido.contracts.OrderEvents.OrderCancelled;
import mx.surtido.contracts.OrderEvents.OrderConfirmed;
import mx.surtido.contracts.OrderEvents.OrderExpired;
import mx.surtido.contracts.OrderEvents.OrderPlaced;
import mx.surtido.contracts.OrderEvents.OrderRejected;
import mx.surtido.contracts.OrderEvents.PaymentRequested;
import mx.surtido.contracts.OrderEvents.PricedLine;
import mx.surtido.contracts.OrderEvents.RefundRequested;
import mx.surtido.contracts.OrderEvents.RequestedLine;
import mx.surtido.contracts.OrderEvents.Store;
import mx.surtido.contracts.PaymentEvents.PaymentCompleted;
import mx.surtido.contracts.PaymentEvents.PaymentFailed;
import mx.surtido.contracts.ShippingEvents.ShipmentStatusChanged;
import mx.surtido.contracts.Topics;
import mx.surtido.messaging.Inbox;
import mx.surtido.messaging.Outbox;

/**
 * Orchestrates an order: place it, wait for inventory, wait for payment, then
 * follow the delivery. Each step locks the order row, checks the transition
 * is allowed, records it in the timeline, and writes the next command to the
 * outbox, all in one transaction.
 */
@Service
public class OrderSaga {

    public enum PaymentMethod { CARD, SPEI }

    private record Locked(UUID id, UUID storeId, OrderStatus status, String method, Long totalCents, Instant createdAt) {}

    private final JdbcClient db;
    private final Inbox inbox;
    private final Outbox outbox;
    private final MeterRegistry metrics;
    private final Duration paymentWindow;

    public OrderSaga(JdbcClient db, Inbox inbox, Outbox outbox, MeterRegistry metrics,
                     @Value("${surtido.payment-window}") Duration paymentWindow) {
        this.db = db;
        this.inbox = inbox;
        this.outbox = outbox;
        this.metrics = metrics;
        this.paymentWindow = paymentWindow;
    }

    // --- Commands from the store -------------------------------------------

    @Transactional
    public UUID place(UUID storeId, PaymentMethod method, List<RequestedLine> lines) {
        if (lines.isEmpty() || lines.size() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An order needs between 1 and 40 lines");
        }
        var seen = new HashSet<UUID>();
        for (RequestedLine line : lines) {
            if (line.quantity() < 1 || line.quantity() > 500) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantities go from 1 to 500 packs");
            }
            if (!seen.add(line.productId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each product can appear once per order");
            }
        }
        store(storeId);

        UUID orderId = db.sql("INSERT INTO orders (store_id, status, payment_method) VALUES (?, 'PENDING_STOCK', ?) RETURNING id")
                .params(storeId, method.name())
                .query(UUID.class)
                .single();
        for (RequestedLine line : lines) {
            db.sql("INSERT INTO order_lines (order_id, product_id, quantity) VALUES (?, ?, ?)")
                    .params(orderId, line.productId(), line.quantity())
                    .update();
        }
        timeline(orderId, "PLACED", "Pedido recibido con " + lines.size() + (lines.size() == 1 ? " producto" : " productos"));
        outbox.publish(Topics.ORDERS, orderId, new OrderPlaced(orderId, storeId, lines));
        metrics.counter("orders.placed", "method", method.name()).increment();
        return orderId;
    }

    @Transactional
    public void cancel(UUID orderId, UUID storeId) {
        Locked order = lock(orderId);
        if (!order.storeId().equals(storeId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        if (!order.status().canMoveTo(OrderStatus.CANCELLED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order can no longer be cancelled");
        }
        move(order, OrderStatus.CANCELLED, "Cancelado por la tienda");
        timeline(orderId, "CANCELLED", "La tienda canceló el pedido");
        outbox.publish(Topics.ORDERS, orderId, new OrderCancelled(orderId, storeId));
    }

    // --- Replies from inventory --------------------------------------------

    @Transactional
    public void onStockReserved(UUID messageId, StockReserved reserved) {
        if (!inbox.firstDelivery(messageId)) return;
        Locked order = lock(reserved.orderId());
        if (!order.status().canMoveTo(OrderStatus.AWAITING_PAYMENT)) {
            // Cancelled while inventory was working; the cancel already asked for a release.
            return;
        }
        for (PricedLine line : reserved.lines()) {
            db.sql("UPDATE order_lines SET product_name = ?, unit_price_cents = ? WHERE order_id = ? AND product_id = ?")
                    .params(line.name(), line.unitPriceCents(), order.id(), line.productId())
                    .update();
        }
        Instant expiresAt = Instant.now().plus(paymentWindow);
        db.sql("UPDATE orders SET status = 'AWAITING_PAYMENT', total_cents = ?, expires_at = ?, updated_at = now() WHERE id = ?")
                .params(reserved.totalCents(), OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC), order.id())
                .update();
        timeline(order.id(), "STOCK_RESERVED", "Mercancía apartada en el almacén");
        outbox.publish(Topics.ORDERS, order.id(),
                new PaymentRequested(order.id(), order.storeId(), reserved.totalCents(), order.method(), expiresAt));
    }

    @Transactional
    public void onStockRejected(UUID messageId, StockRejected rejected) {
        if (!inbox.firstDelivery(messageId)) return;
        Locked order = lock(rejected.orderId());
        if (!order.status().canMoveTo(OrderStatus.REJECTED)) return;
        String reason = "Sin existencias suficientes de " + rejected.productName()
                + " (pediste " + rejected.requested() + ", quedan " + rejected.available() + ")";
        move(order, OrderStatus.REJECTED, reason);
        timeline(order.id(), "STOCK_REJECTED", reason);
        outbox.publish(Topics.ORDERS, order.id(), new OrderRejected(order.id(), store(order.storeId()), reason));
        metrics.counter("orders.finished", "status", "REJECTED").increment();
    }

    @Transactional
    public void onStockReleased(UUID messageId, StockReleased released) {
        if (!inbox.firstDelivery(messageId)) return;
        timeline(released.orderId(), "STOCK_RELEASED", "La mercancía apartada volvió al inventario");
    }

    // --- Replies from payments ---------------------------------------------

    @Transactional
    public void onPaymentCompleted(UUID messageId, PaymentCompleted payment) {
        if (!inbox.firstDelivery(messageId)) return;
        Locked order = lock(payment.orderId());
        if (order.status().refundsLatePayments()) {
            // The window closed and the stock is back on sale: the money goes back.
            timeline(order.id(), "REFUNDED", "El pago llegó tarde y se reembolsó");
            outbox.publish(Topics.ORDERS, order.id(), new RefundRequested(order.id(), "order_" + order.status().name().toLowerCase()));
            return;
        }
        if (!order.status().canMoveTo(OrderStatus.CONFIRMED)) return;
        move(order, OrderStatus.CONFIRMED, null);
        timeline(order.id(), "PAID", payment.method().equals("SPEI") ? "Transferencia SPEI recibida" : "Pago con tarjeta aprobado");

        List<PricedLine> lines = db.sql("""
                SELECT product_id, product_name, quantity, unit_price_cents FROM order_lines WHERE order_id = ? ORDER BY product_name
                """)
                .param(order.id())
                .query((rs, i) -> new PricedLine(
                        rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                        rs.getInt("quantity"), rs.getLong("unit_price_cents")))
                .list();
        outbox.publish(Topics.ORDERS, order.id(), new OrderConfirmed(order.id(), store(order.storeId()), order.totalCents(), lines));
        metrics.timer("orders.time_to_confirm").record(Duration.between(order.createdAt(), Instant.now()));
    }

    @Transactional
    public void onPaymentFailed(UUID messageId, PaymentFailed payment) {
        if (!inbox.firstDelivery(messageId)) return;
        timeline(payment.orderId(), "PAYMENT_FAILED", "Tarjeta rechazada: " + payment.reason() + ". Puedes intentar con otra.");
    }

    // --- Replies from shipping ---------------------------------------------

    @Transactional
    public void onShipmentStatusChanged(UUID messageId, ShipmentStatusChanged change) {
        if (!inbox.firstDelivery(messageId)) return;
        Locked order = lock(change.orderId());
        OrderStatus next = switch (change.status()) {
            case "PREPARING" -> OrderStatus.PREPARING;
            case "IN_TRANSIT" -> OrderStatus.IN_TRANSIT;
            case "DELIVERED" -> OrderStatus.DELIVERED;
            default -> null;
        };
        if (next == null || !order.status().canMoveTo(next)) return;
        move(order, next, null);
        String detail = switch (next) {
            case PREPARING -> "El almacén está preparando tu pedido";
            case IN_TRANSIT -> "En ruta con " + change.driver();
            default -> "Entregado en la tienda";
        };
        timeline(order.id(), next.name(), detail);
        if (next == OrderStatus.DELIVERED) metrics.counter("orders.finished", "status", "DELIVERED").increment();
    }

    // --- Payment window -----------------------------------------------------

    /**
     * Expires orders whose payment window closed. SKIP LOCKED leaves alone an
     * order a payment is confirming at this very moment.
     */
    @Transactional
    public int expireOverdue() {
        List<Locked> overdue = db.sql("""
                SELECT id, store_id, status, payment_method, total_cents, created_at FROM orders
                WHERE status = 'AWAITING_PAYMENT' AND expires_at < now()
                ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED
                """)
                .query((rs, i) -> locked(rs))
                .list();
        for (Locked order : overdue) {
            move(order, OrderStatus.EXPIRED, "No se recibió el pago a tiempo");
            timeline(order.id(), "EXPIRED", "Venció el plazo para pagar");
            outbox.publish(Topics.ORDERS, order.id(), new OrderExpired(order.id(), store(order.storeId()), order.totalCents()));
            metrics.counter("orders.finished", "status", "EXPIRED").increment();
        }
        return overdue.size();
    }

    // --- Helpers ------------------------------------------------------------

    private Locked lock(UUID orderId) {
        return db.sql("SELECT id, store_id, status, payment_method, total_cents, created_at FROM orders WHERE id = ? FOR UPDATE")
                .param(orderId)
                .query((rs, i) -> locked(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    private static Locked locked(java.sql.ResultSet rs) throws java.sql.SQLException {
        long total = rs.getLong("total_cents");
        return new Locked(
                rs.getObject("id", UUID.class),
                rs.getObject("store_id", UUID.class),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("payment_method"),
                rs.wasNull() ? null : total,
                rs.getTimestamp("created_at").toInstant());
    }

    private void move(Locked order, OrderStatus next, String reason) {
        if (!order.status().canMoveTo(next)) {
            throw new IllegalStateException("Order " + order.id() + " cannot go from " + order.status() + " to " + next);
        }
        db.sql("UPDATE orders SET status = ?, failure_reason = coalesce(?, failure_reason), updated_at = now() WHERE id = ?")
                .params(next.name(), reason, order.id())
                .update();
    }

    private void timeline(UUID orderId, String step, String detail) {
        db.sql("INSERT INTO order_timeline (order_id, step, detail) VALUES (?, ?, ?)").params(orderId, step, detail).update();
    }

    Store store(UUID storeId) {
        return db.sql("SELECT id, name, email, address, city FROM stores WHERE id = ?")
                .param(storeId)
                .query((rs, i) -> new Store(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
                        rs.getString("address"), rs.getString("city")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown store"));
    }
}
