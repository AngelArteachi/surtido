package mx.surtido.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import mx.surtido.contracts.EventTypes;
import tools.jackson.databind.json.JsonMapper;

/** A decoded message: its id for deduplication and its typed body. */
public record IncomingEvent(UUID id, Object body) {

    public static IncomingEvent decode(ConsumerRecord<String, String> record, JsonMapper json) {
        String type = header(record, Headers.EVENT_TYPE);
        String id = header(record, Headers.EVENT_ID);
        return new IncomingEvent(UUID.fromString(id), json.readValue(record.value(), EventTypes.classFor(type)));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) throw new IllegalArgumentException("Message without " + name + " header");
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
