package mx.surtido.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import mx.surtido.contracts.InventoryEvents.StockReserved;
import mx.surtido.contracts.OrderEvents.PricedLine;
import mx.surtido.contracts.OrderEvents.RequestedLine;
import mx.surtido.contracts.PaymentEvents.PaymentCompleted;
import mx.surtido.contracts.ShippingEvents.ShipmentStatusChanged;

@Testcontainers
@SpringBootTest(properties = {
    "eureka.client.enabled=false",
    "spring.kafka.listener.auto-startup=false",
    "spring.kafka.admin.auto-create=false",
    "surtido.outbox.relay-enabled=false",
    "surtido.payment-window=PT1S",
})
class OrderSagaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static final UUID STORE = UUID.fromString("0b6a8f5e-3c1d-4f0a-9b7e-1a2c3d4e5f01");

    @Autowired OrderSaga saga;
    @Autowired JdbcClient db;

    private String status(UUID orderId) {
        return db.sql("SELECT status FROM orders WHERE id = ?").param(orderId).query(String.class).single();
    }

    private List<String> outboxTypes(UUID orderId) {
        return db.sql("SELECT event_type FROM outbox WHERE message_key = ? ORDER BY created_at")
                .param(orderId.toString()).query(String.class).list();
    }

    private UUID placeAndReserve(long unitPrice) {
        UUID product = UUID.randomUUID();
        UUID orderId = saga.place(STORE, OrderSaga.PaymentMethod.CARD, List.of(new RequestedLine(product, 4)));
        saga.onStockReserved(UUID.randomUUID(),
                new StockReserved(orderId, List.of(new PricedLine(product, "Arroz 1 kg", 4, unitPrice)), 4 * unitPrice));
        return orderId;
    }

    @Test
    void walksAnOrderFromPlacedToDelivered() {
        UUID orderId = placeAndReserve(29_900);
        assertThat(status(orderId)).isEqualTo("AWAITING_PAYMENT");

        UUID payment = UUID.randomUUID();
        saga.onPaymentCompleted(payment, new PaymentCompleted(orderId, UUID.randomUUID(), 119_600, "CARD"));
        saga.onPaymentCompleted(payment, new PaymentCompleted(orderId, UUID.randomUUID(), 119_600, "CARD"));
        assertThat(status(orderId)).isEqualTo("CONFIRMED");

        for (String step : List.of("PREPARING", "IN_TRANSIT", "DELIVERED")) {
            saga.onShipmentStatusChanged(UUID.randomUUID(), new ShipmentStatusChanged(orderId, UUID.randomUUID(), step, null, "Luis"));
        }
        assertThat(status(orderId)).isEqualTo("DELIVERED");
        assertThat(outboxTypes(orderId)).containsExactly("OrderPlaced", "PaymentRequested", "OrderConfirmed");
    }

    @Test
    void ignoresShipmentEventsThatArriveOutOfOrder() {
        UUID orderId = placeAndReserve(10_000);
        saga.onShipmentStatusChanged(UUID.randomUUID(), new ShipmentStatusChanged(orderId, UUID.randomUUID(), "DELIVERED", null, "Luis"));
        assertThat(status(orderId)).isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void expiresUnpaidOrdersAndRefundsMoneyThatArrivesLate() throws InterruptedException {
        UUID orderId = placeAndReserve(10_000);
        Thread.sleep(1_200);
        saga.expireOverdue();
        assertThat(status(orderId)).isEqualTo("EXPIRED");

        saga.onPaymentCompleted(UUID.randomUUID(), new PaymentCompleted(orderId, UUID.randomUUID(), 40_000, "SPEI"));
        assertThat(status(orderId)).isEqualTo("EXPIRED");
        assertThat(outboxTypes(orderId)).containsExactly("OrderPlaced", "PaymentRequested", "OrderExpired", "RefundRequested");
    }

    @Test
    void rejectsInvalidOrdersUpFront() {
        UUID product = UUID.randomUUID();
        assertThatThrownBy(() -> saga.place(STORE, OrderSaga.PaymentMethod.SPEI,
                List.of(new RequestedLine(product, 1), new RequestedLine(product, 2))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> saga.place(UUID.randomUUID(), OrderSaga.PaymentMethod.SPEI, List.of(new RequestedLine(product, 1))))
                .isInstanceOf(ResponseStatusException.class);
    }
}
