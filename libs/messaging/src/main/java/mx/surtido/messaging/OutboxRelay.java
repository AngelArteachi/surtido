package mx.surtido.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves outbox rows to Kafka. Rows are locked with SKIP LOCKED, so several
 * replicas can relay at once without sending a row twice. A crash after
 * sending but before marking produces a duplicate, which consumers ignore.
 */
@Component
@ConditionalOnProperty(name = "surtido.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private record Row(UUID id, String topic, String key, String type, String payload) {}

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 100)
    public void relay() {
        try {
            Integer sent;
            do {
                sent = tx.execute(status -> relayBatch());
            } while (sent != null && sent > 0);
        } catch (Exception e) {
            log.error("outbox relay failed", e);
        }
    }

    int relayBatch() {
        List<Row> rows = jdbc.query(
                """
                SELECT id, topic, message_key, event_type, payload FROM outbox
                WHERE published_at IS NULL ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED
                """,
                (rs, i) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("event_type"),
                        rs.getString("payload")));
        for (Row row : rows) {
            var record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
            record.headers().add(Headers.EVENT_ID, row.id().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(Headers.EVENT_TYPE, row.type().getBytes(StandardCharsets.UTF_8));
            try {
                kafka.send(record).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Kafka send failed for outbox row " + row.id(), e);
            }
            metrics.counter("outbox.relayed", "type", row.type()).increment();
        }
        if (!rows.isEmpty()) {
            jdbc.update(
                    "UPDATE outbox SET published_at = now() WHERE id = ANY (?)",
                    (Object) rows.stream().map(Row::id).toArray(UUID[]::new));
        }
        return rows.size();
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanUp() {
        jdbc.update("DELETE FROM outbox WHERE published_at < now() - interval '1 day'");
    }
}
