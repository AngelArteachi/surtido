package mx.surtido.shipping;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import mx.surtido.contracts.OrderEvents.OrderConfirmed;
import mx.surtido.contracts.OrderEvents.Store;
import mx.surtido.contracts.ShippingEvents.ShipmentStatusChanged;
import mx.surtido.contracts.Topics;
import mx.surtido.messaging.Inbox;
import mx.surtido.messaging.IncomingEvent;
import mx.surtido.messaging.Outbox;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deliveries. A shipment is opened when an order is confirmed and the
 * warehouse moves it along: pending, preparing, in transit, delivered.
 */
@SpringBootApplication(scanBasePackages = "mx.surtido")
public class ShippingApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShippingApplication.class, args);
    }

    enum Status {
        PENDING, PREPARING, IN_TRANSIT, DELIVERED;

        Status next() {
            return this == DELIVERED ? null : values()[ordinal() + 1];
        }
    }

    static final List<String> DRIVERS = List.of("Luis Pérez", "Ana Méndez", "Carlos Ruiz", "Sofía Gómez");

    record Shipment(
            UUID id, UUID orderId, String storeName, String address, String city, int packs, long totalCents,
            String status, String driver, Instant createdAt, Instant updatedAt) {}

    @Component
    static class Shipments {
        private static final String COLUMNS =
                "id, order_id, store_id, store_name, store_email, address, city, packs, total_cents, status, driver, created_at, updated_at";

        private final JdbcClient db;
        private final Inbox inbox;
        private final Outbox outbox;
        private final MeterRegistry metrics;

        Shipments(JdbcClient db, Inbox inbox, Outbox outbox, MeterRegistry metrics) {
            this.db = db;
            this.inbox = inbox;
            this.outbox = outbox;
            this.metrics = metrics;
        }

        private static Shipment map(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new Shipment(
                    rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("store_name"),
                    rs.getString("address"), rs.getString("city"), rs.getInt("packs"), rs.getLong("total_cents"),
                    rs.getString("status"), rs.getString("driver"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }

        @Transactional
        void open(UUID messageId, OrderConfirmed order) {
            if (!inbox.firstDelivery(messageId)) return;
            int packs = order.lines().stream().mapToInt(l -> l.quantity()).sum();
            db.sql("""
                    INSERT INTO shipments (order_id, store_id, store_name, store_email, address, city, packs, total_cents, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING') ON CONFLICT (order_id) DO NOTHING
                    """)
                    .params(order.orderId(), order.store().id(), order.store().name(), order.store().email(),
                            order.store().address(), order.store().city(), packs, order.totalCents())
                    .update();
        }

        List<Shipment> list(String status) {
            return db.sql("SELECT " + COLUMNS + """
                     FROM shipments
                    WHERE (CAST(:status AS text) IS NULL OR status = :status)
                      AND (status <> 'DELIVERED' OR delivered_at > now() - interval '24 hours')
                    ORDER BY created_at
                    """)
                    .param("status", status)
                    .query((rs, i) -> map(rs))
                    .list();
        }

        /** Moves a shipment one step. The row lock stops two people in the warehouse from double-advancing it. */
        @Transactional
        Shipment advance(UUID id) {
            record Row(Shipment shipment, Store store) {}
            Row row = db.sql("SELECT " + COLUMNS + " FROM shipments WHERE id = ? FOR UPDATE")
                    .param(id)
                    .query((rs, i) -> new Row(map(rs), new Store(
                            rs.getObject("store_id", UUID.class), rs.getString("store_name"), rs.getString("store_email"),
                            rs.getString("address"), rs.getString("city"))))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found"));
            Status next = Status.valueOf(row.shipment().status()).next();
            if (next == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already delivered");

            String driver = row.shipment().driver();
            if (next == Status.IN_TRANSIT) driver = DRIVERS.get(Math.floorMod(id.hashCode(), DRIVERS.size()));
            db.sql("""
                    UPDATE shipments SET status = ?, driver = ?, updated_at = now(),
                      delivered_at = CASE WHEN ? = 'DELIVERED' THEN now() ELSE delivered_at END
                    WHERE id = ?
                    """)
                    .params(next.name(), driver, next.name(), id)
                    .update();
            outbox.publish(Topics.SHIPPING, row.shipment().orderId(),
                    new ShipmentStatusChanged(row.shipment().orderId(), id, next.name(), row.store(), driver));
            metrics.counter("shipments.advanced", "to", next.name()).increment();
            return db.sql("SELECT " + COLUMNS + " FROM shipments WHERE id = ?").param(id).query((rs, i) -> map(rs)).single();
        }
    }

    @Component
    static class OrderEventsListener {
        private final Shipments shipments;
        private final JsonMapper json;

        OrderEventsListener(Shipments shipments, JsonMapper json) {
            this.shipments = shipments;
            this.json = json;
        }

        @KafkaListener(topics = Topics.ORDERS, groupId = "shipping")
        public void onOrderEvent(ConsumerRecord<String, String> record) {
            IncomingEvent event = IncomingEvent.decode(record, json);
            if (event.body() instanceof OrderConfirmed confirmed) shipments.open(event.id(), confirmed);
        }
    }

    @RestController
    static class ShipmentController {
        private final Shipments shipments;

        ShipmentController(Shipments shipments) {
            this.shipments = shipments;
        }

        @GetMapping("/shipments")
        List<Shipment> list(@RequestParam(required = false) String status) {
            return shipments.list(status);
        }

        @PostMapping("/shipments/{id}/advance")
        Shipment advance(@PathVariable UUID id) {
            return shipments.advance(id);
        }
    }
}
