package mx.surtido.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import mx.surtido.contracts.EventTypes;
import tools.jackson.databind.json.JsonMapper;

/** Records an event to publish. Must run inside the transaction that changes the state it describes. */
@Component
public class Outbox {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public Outbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, UUID orderId, Object event) {
        jdbc.update(
                "INSERT INTO outbox (id, topic, message_key, event_type, payload) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                topic,
                orderId.toString(),
                EventTypes.nameOf(event.getClass()),
                json.writeValueAsString(event));
    }
}
