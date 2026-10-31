package mx.surtido.orders;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import mx.surtido.contracts.InventoryEvents.StockRejected;
import mx.surtido.contracts.InventoryEvents.StockReleased;
import mx.surtido.contracts.InventoryEvents.StockReserved;
import mx.surtido.contracts.PaymentEvents.PaymentCompleted;
import mx.surtido.contracts.PaymentEvents.PaymentFailed;
import mx.surtido.contracts.ShippingEvents.ShipmentStatusChanged;
import mx.surtido.contracts.Topics;
import mx.surtido.messaging.IncomingEvent;
import tools.jackson.databind.json.JsonMapper;

/** Feeds the saga with the replies of the other services. */
@Component
public class SagaListener {
    private final OrderSaga saga;
    private final JsonMapper json;

    public SagaListener(OrderSaga saga, JsonMapper json) {
        this.saga = saga;
        this.json = json;
    }

    @KafkaListener(topics = {Topics.INVENTORY, Topics.PAYMENTS, Topics.SHIPPING}, groupId = "orders")
    public void onReply(ConsumerRecord<String, String> record) {
        IncomingEvent event = IncomingEvent.decode(record, json);
        switch (event.body()) {
            case StockReserved e -> saga.onStockReserved(event.id(), e);
            case StockRejected e -> saga.onStockRejected(event.id(), e);
            case StockReleased e -> saga.onStockReleased(event.id(), e);
            case PaymentCompleted e -> saga.onPaymentCompleted(event.id(), e);
            case PaymentFailed e -> saga.onPaymentFailed(event.id(), e);
            case ShipmentStatusChanged e -> saga.onShipmentStatusChanged(event.id(), e);
            default -> {
                // Not part of the saga.
            }
        }
    }

    @Scheduled(fixedDelay = 2_000)
    public void expireOverdueOrders() {
        saga.expireOverdue();
    }
}
