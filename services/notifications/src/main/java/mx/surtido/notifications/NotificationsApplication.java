package mx.surtido.notifications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import mx.surtido.contracts.OrderEvents.OrderConfirmed;
import mx.surtido.contracts.OrderEvents.OrderExpired;
import mx.surtido.contracts.OrderEvents.OrderRejected;
import mx.surtido.contracts.ShippingEvents.ShipmentStatusChanged;
import mx.surtido.contracts.Topics;
import mx.surtido.messaging.IncomingEvent;
import tools.jackson.databind.json.JsonMapper;

/**
 * Emails the stores at the moments that matter to them. A failed send does
 * not block the topic: the message moves to a retry topic and comes back
 * after 2, 4 and 8 seconds; after that it lands in the dead-letter topic and
 * is recorded as failed for someone to look at.
 */
@SpringBootApplication(scanBasePackages = "mx.surtido")
public class NotificationsApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationsApplication.class, args);
    }

    @Component
    static class Mailer {
        private static final Logger log = LoggerFactory.getLogger(Mailer.class);

        private final JavaMailSender mail;
        private final JdbcClient db;
        private final JsonMapper json;
        private final MeterRegistry metrics;
        private final String from;
        private final double failureRate;

        Mailer(JavaMailSender mail, JdbcClient db, JsonMapper json, MeterRegistry metrics,
               @Value("${surtido.mail-from}") String from, @Value("${surtido.mail-failure-rate}") double failureRate) {
            this.mail = mail;
            this.db = db;
            this.json = json;
            this.metrics = metrics;
            this.from = from;
            this.failureRate = failureRate;
        }

        @RetryableTopic(attempts = "4", backOff = @BackOff(delay = 2_000, multiplier = 2.0), dltTopicSuffix = "-dlt", retryTopicSuffix = "-retry")
        @KafkaListener(topics = {Topics.ORDERS, Topics.SHIPPING}, groupId = "notifications")
        public void onEvent(ConsumerRecord<String, String> record,
                            @Header(name = KafkaHeaders.DELIVERY_ATTEMPT, required = false) Integer attempt) throws Exception {
            IncomingEvent event = IncomingEvent.decode(record, json);
            Emails.Email email = switch (event.body()) {
                case OrderConfirmed e -> Emails.confirmed(e);
                case OrderRejected e -> Emails.rejected(e);
                case OrderExpired e -> Emails.expired(e);
                case ShipmentStatusChanged e when e.status().equals("IN_TRANSIT") || e.status().equals("DELIVERED") -> Emails.shipment(e);
                default -> null;
            };
            if (email == null || alreadySent(event.id())) return;

            if (ThreadLocalRandom.current().nextDouble() < failureRate) {
                metrics.counter("notifications.sent", "result", "retry").increment();
                throw new IllegalStateException("Simulated mail server outage");
            }
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(email.html(), true);
            mail.send(message);

            db.sql("""
                    INSERT INTO notifications (event_id, order_id, recipient, subject, status, attempts)
                    VALUES (?, ?, ?, ?, 'SENT', ?) ON CONFLICT (event_id) DO NOTHING
                    """)
                    .params(event.id(), UUID.fromString(record.key()), email.to(), email.subject(), attempt == null ? 1 : attempt)
                    .update();
            metrics.counter("notifications.sent", "result", "sent").increment();
        }

        @DltHandler
        public void onDeadLetter(ConsumerRecord<String, String> record,
                                 @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String error) {
            IncomingEvent event = IncomingEvent.decode(record, json);
            log.error("giving up on the email for event {}: {}", event.id(), error);
            db.sql("""
                    INSERT INTO notifications (event_id, order_id, recipient, subject, status, attempts, last_error)
                    VALUES (?, ?, '', ?, 'FAILED', 4, ?) ON CONFLICT (event_id) DO NOTHING
                    """)
                    .params(event.id(), UUID.fromString(record.key()), event.body().getClass().getSimpleName(), error)
                    .update();
            metrics.counter("notifications.sent", "result", "dead_letter").increment();
        }

        private boolean alreadySent(UUID eventId) {
            return db.sql("SELECT EXISTS (SELECT 1 FROM notifications WHERE event_id = ? AND status = 'SENT')")
                    .param(eventId)
                    .query(Boolean.class)
                    .single();
        }
    }

    record Notification(long id, UUID orderId, String recipient, String subject, String status, int attempts, String lastError, Instant createdAt) {}

    @RestController
    static class NotificationController {
        private final JdbcClient db;

        NotificationController(JdbcClient db) {
            this.db = db;
        }

        @GetMapping("/notifications")
        List<Notification> recent() {
            return db.sql("""
                    SELECT id, order_id, recipient, subject, status, attempts, last_error, created_at
                    FROM notifications ORDER BY created_at DESC LIMIT 50
                    """)
                    .query((rs, i) -> new Notification(
                            rs.getLong("id"), rs.getObject("order_id", UUID.class), rs.getString("recipient"),
                            rs.getString("subject"), rs.getString("status"), rs.getInt("attempts"),
                            rs.getString("last_error"), rs.getTimestamp("created_at").toInstant()))
                    .list();
        }
    }
}
