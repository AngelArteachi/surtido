package mx.surtido.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import mx.surtido.contracts.OrderEvents.OrderPlaced;
import mx.surtido.contracts.OrderEvents.RequestedLine;

@Testcontainers
@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "spring.kafka.listener.auto-startup=false",
    "surtido.outbox.relay-enabled=false",
    "spring.kafka.admin.auto-create=false",
    "spring.datasource.hikari.maximum-pool-size=30",
})
class ReservationsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired Reservations reservations;
    @Autowired JdbcClient db;

    private UUID product(String sku) {
        return db.sql("SELECT id FROM products WHERE sku = ?").param(sku).query(UUID.class).single();
    }

    private int stock(UUID id) {
        return db.sql("SELECT stock FROM products WHERE id = ?").param(id).query(Integer.class).single();
    }

    private void setStock(UUID id, int stock) {
        db.sql("UPDATE products SET stock = ? WHERE id = ?").params(stock, id).update();
    }

    /** Places an order the way the listener does: reserve, or reject if any line is short. */
    private boolean place(OrderPlaced order) {
        UUID messageId = UUID.randomUUID();
        try {
            reservations.reserve(messageId, order);
            return true;
        } catch (Reservations.InsufficientStock shortage) {
            reservations.reject(messageId, order, shortage);
            return false;
        }
    }

    @Test
    void neverOversellsAndRollsBackWholeOrdersWhenOneLineIsShort() throws Exception {
        UUID scarce = product("BOT-CHI-60");
        UUID plenty = product("BEB-AGU-1L");
        setStock(scarce, 50);
        setStock(plenty, 1_000);

        // 200 stores order 1 scarce + 2 plentiful at the same moment.
        List<Callable<Boolean>> orders = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            var order = new OrderPlaced(UUID.randomUUID(), UUID.randomUUID(),
                    List.of(new RequestedLine(plenty, 2), new RequestedLine(scarce, 1)));
            orders.add(() -> place(order));
        }
        int accepted = 0;
        try (var pool = Executors.newFixedThreadPool(25)) {
            for (Future<Boolean> result : pool.invokeAll(orders)) if (result.get()) accepted++;
        }

        assertThat(accepted).isEqualTo(50);
        assertThat(stock(scarce)).isZero();
        // Rejected orders did not keep the plentiful units they had already taken.
        assertThat(stock(plenty)).isEqualTo(1_000 - 50 * 2);
        assertThat(db.sql("SELECT count(*) FROM outbox WHERE event_type = 'StockRejected'").query(Integer.class).single())
                .isGreaterThanOrEqualTo(150);
    }

    @Test
    void ignoresARedeliveredOrderAndReleasesStockOnlyOnce() {
        UUID product = product("ABA-ARR-1K");
        setStock(product, 100);
        var order = new OrderPlaced(UUID.randomUUID(), UUID.randomUUID(), List.of(new RequestedLine(product, 10)));
        UUID messageId = UUID.randomUUID();

        reservations.reserve(messageId, order);
        reservations.reserve(messageId, order);
        assertThat(stock(product)).isEqualTo(90);

        UUID expiry = UUID.randomUUID();
        reservations.release(expiry, order.orderId());
        reservations.release(expiry, order.orderId());
        reservations.release(UUID.randomUUID(), order.orderId());
        assertThat(stock(product)).isEqualTo(100);
    }

    @Test
    void pricesComeFromTheCatalogNotFromTheOrder() {
        UUID product = product("LAC-LEC-1L");
        setStock(product, 100);
        var reserved = reservations.reserve(UUID.randomUUID(),
                new OrderPlaced(UUID.randomUUID(), UUID.randomUUID(), List.of(new RequestedLine(product, 3))));
        assertThat(reserved.totalCents()).isEqualTo(3 * 30_600L);
        assertThat(reserved.lines().getFirst().name()).isEqualTo("Leche entera 1 L");
    }
}
