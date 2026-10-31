package mx.surtido.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import mx.surtido.contracts.InventoryEvents.StockRejected;
import mx.surtido.contracts.InventoryEvents.StockReleased;
import mx.surtido.contracts.InventoryEvents.StockReserved;
import mx.surtido.contracts.OrderEvents.OrderPlaced;
import mx.surtido.contracts.OrderEvents.PricedLine;
import mx.surtido.contracts.OrderEvents.RequestedLine;
import mx.surtido.contracts.Topics;
import mx.surtido.messaging.Inbox;
import mx.surtido.messaging.Outbox;

/**
 * Stock reservations for the order saga. All lines of an order are reserved
 * in one transaction or none are.
 */
@Service
public class Reservations {

    public static class InsufficientStock extends RuntimeException {
        final UUID productId;
        final String productName;
        final int requested;
        final int available;

        InsufficientStock(UUID productId, String productName, int requested, int available) {
            super("Not enough stock of " + productName);
            this.productId = productId;
            this.productName = productName;
            this.requested = requested;
            this.available = available;
        }
    }

    private final JdbcClient db;
    private final Inbox inbox;
    private final Outbox outbox;
    private final MeterRegistry metrics;

    public Reservations(JdbcClient db, Inbox inbox, Outbox outbox, MeterRegistry metrics) {
        this.db = db;
        this.inbox = inbox;
        this.outbox = outbox;
        this.metrics = metrics;
    }

    public boolean alreadyProcessed(UUID messageId) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM processed_messages WHERE message_id = ?)")
                .param(messageId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Each line is a conditional UPDATE: Postgres locks the product row and
     * only decrements if enough stock is left, so concurrent orders can never
     * take the same units. Lines are applied in product-id order so two
     * orders that share products always lock them in the same order and
     * cannot deadlock.
     */
    @Transactional
    public StockReserved reserve(UUID messageId, OrderPlaced order) {
        if (!inbox.firstDelivery(messageId)) return null;

        List<RequestedLine> lines = new ArrayList<>(order.lines());
        lines.sort(Comparator.comparing(RequestedLine::productId));

        db.sql("INSERT INTO reservations (order_id, status) VALUES (?, 'RESERVED')").param(order.orderId()).update();
        List<PricedLine> priced = new ArrayList<>();
        for (RequestedLine line : lines) {
            var updated = db.sql("""
                    UPDATE products SET stock = stock - :quantity, updated_at = now()
                    WHERE id = :id AND active AND stock >= :quantity
                    RETURNING name, price_cents
                    """)
                    .param("id", line.productId())
                    .param("quantity", line.quantity())
                    .query((rs, i) -> new PricedLine(line.productId(), rs.getString("name"), line.quantity(), rs.getLong("price_cents")))
                    .optional();
            if (updated.isEmpty()) {
                var current = db.sql("SELECT name, stock FROM products WHERE id = ?")
                        .param(line.productId())
                        .query((rs, i) -> new InsufficientStock(line.productId(), rs.getString("name"), line.quantity(), rs.getInt("stock")))
                        .optional()
                        .orElseGet(() -> new InsufficientStock(line.productId(), "Producto desconocido", line.quantity(), 0));
                // Rolls back every line already taken for this order.
                throw current;
            }
            PricedLine reserved = updated.get();
            priced.add(reserved);
            db.sql("INSERT INTO reservation_lines (order_id, product_id, quantity, unit_price_cents) VALUES (?, ?, ?, ?)")
                    .params(order.orderId(), reserved.productId(), reserved.quantity(), reserved.unitPriceCents())
                    .update();
        }

        long total = priced.stream().mapToLong(l -> l.unitPriceCents() * l.quantity()).sum();
        var event = new StockReserved(order.orderId(), priced, total);
        outbox.publish(Topics.INVENTORY, order.orderId(), event);
        metrics.counter("inventory.reservations", "result", "reserved").increment();
        return event;
    }

    /** Runs in its own transaction after `reserve` rolled back. */
    @Transactional
    public void reject(UUID messageId, OrderPlaced order, InsufficientStock shortage) {
        if (!inbox.firstDelivery(messageId)) return;
        outbox.publish(Topics.INVENTORY, order.orderId(),
                new StockRejected(order.orderId(), shortage.productId, shortage.productName, shortage.requested, shortage.available));
        metrics.counter("inventory.reservations", "result", "rejected").increment();
    }

    /** Puts reserved units back on the shelf when an order expires or is cancelled. */
    @Transactional
    public void release(UUID messageId, UUID orderId) {
        if (!inbox.firstDelivery(messageId)) return;
        int changed = db.sql("UPDATE reservations SET status = 'RELEASED', updated_at = now() WHERE order_id = ? AND status = 'RESERVED'")
                .param(orderId)
                .update();
        if (changed == 0) return;
        db.sql("""
                UPDATE products p SET stock = p.stock + l.quantity, updated_at = now()
                FROM reservation_lines l WHERE l.order_id = ? AND l.product_id = p.id
                """)
                .param(orderId)
                .update();
        outbox.publish(Topics.INVENTORY, orderId, new StockReleased(orderId));
        metrics.counter("inventory.reservations", "result", "released").increment();
    }

    /** The order was paid: the reserved units are sold for good. */
    @Transactional
    public void commit(UUID messageId, UUID orderId) {
        if (!inbox.firstDelivery(messageId)) return;
        db.sql("UPDATE reservations SET status = 'COMMITTED', updated_at = now() WHERE order_id = ? AND status = 'RESERVED'")
                .param(orderId)
                .update();
    }
}
