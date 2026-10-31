package mx.surtido.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Deduplicates incoming messages inside the handler's own transaction. */
@Component
public class Inbox {
    private final JdbcTemplate jdbc;

    public Inbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True the first time a message id is seen; false for a redelivery. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstDelivery(UUID messageId) {
        return jdbc.update("INSERT INTO processed_messages (message_id) VALUES (?) ON CONFLICT DO NOTHING", messageId) == 1;
    }
}
