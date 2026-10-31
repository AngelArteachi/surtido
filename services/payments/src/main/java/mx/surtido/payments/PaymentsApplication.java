package mx.surtido.payments;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import mx.surtido.contracts.OrderEvents.OrderCancelled;
import mx.surtido.contracts.OrderEvents.OrderExpired;
import mx.surtido.contracts.OrderEvents.PaymentRequested;
import mx.surtido.contracts.OrderEvents.RefundRequested;
import mx.surtido.contracts.Topics;
import mx.surtido.messaging.IncomingEvent;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication(scanBasePackages = "mx.surtido")
public class PaymentsApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }

    @Component
    static class OrderEventsListener {
        private final Payments payments;
        private final JsonMapper json;

        OrderEventsListener(Payments payments, JsonMapper json) {
            this.payments = payments;
            this.json = json;
        }

        @KafkaListener(topics = Topics.ORDERS, groupId = "payments")
        public void onOrderEvent(ConsumerRecord<String, String> record) {
            IncomingEvent event = IncomingEvent.decode(record, json);
            switch (event.body()) {
                case PaymentRequested e -> payments.open(event.id(), e);
                case OrderExpired e -> payments.cancel(event.id(), e.orderId());
                case OrderCancelled e -> payments.cancel(event.id(), e.orderId());
                case RefundRequested e -> payments.refund(event.id(), e.orderId());
                default -> {
                    // Not a payments concern.
                }
            }
        }
    }
}
