package mx.surtido.catalog;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import mx.surtido.contracts.OrderEvents.OrderCancelled;
import mx.surtido.contracts.OrderEvents.OrderConfirmed;
import mx.surtido.contracts.OrderEvents.OrderExpired;
import mx.surtido.contracts.OrderEvents.OrderPlaced;
import mx.surtido.contracts.Topics;
import mx.surtido.messaging.IncomingEvent;
import tools.jackson.databind.json.JsonMapper;

@Component
public class InventoryListener {
    private static final Logger log = LoggerFactory.getLogger(InventoryListener.class);

    private final Reservations reservations;
    private final JsonMapper json;

    public InventoryListener(Reservations reservations, JsonMapper json) {
        this.reservations = reservations;
        this.json = json;
    }

    @KafkaListener(topics = Topics.ORDERS, groupId = "catalog")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        IncomingEvent event = IncomingEvent.decode(record, json);
        switch (event.body()) {
            case OrderPlaced placed -> {
                if (reservations.alreadyProcessed(event.id())) return;
                try {
                    reservations.reserve(event.id(), placed);
                } catch (Reservations.InsufficientStock shortage) {
                    log.info("order {} rejected: {}", placed.orderId(), shortage.getMessage());
                    reservations.reject(event.id(), placed, shortage);
                }
            }
            case OrderExpired expired -> reservations.release(event.id(), expired.orderId());
            case OrderCancelled cancelled -> reservations.release(event.id(), cancelled.orderId());
            case OrderConfirmed confirmed -> reservations.commit(event.id(), confirmed.orderId());
            default -> {
                // Other order events do not concern inventory.
            }
        }
    }
}
