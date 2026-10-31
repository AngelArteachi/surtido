package mx.surtido.contracts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The event type travels in a Kafka header as the record's simple name, so a
 * consumer can decode the JSON body without the producer's class names.
 */
public final class EventTypes {
    private static final Map<String, Class<?>> BY_NAME = new HashMap<>();

    static {
        List.<Class<?>>of(
                OrderEvents.OrderPlaced.class,
                OrderEvents.PaymentRequested.class,
                OrderEvents.OrderConfirmed.class,
                OrderEvents.OrderRejected.class,
                OrderEvents.OrderExpired.class,
                OrderEvents.OrderCancelled.class,
                OrderEvents.RefundRequested.class,
                InventoryEvents.StockReserved.class,
                InventoryEvents.StockRejected.class,
                InventoryEvents.StockReleased.class,
                PaymentEvents.PaymentCompleted.class,
                PaymentEvents.PaymentFailed.class,
                ShippingEvents.ShipmentStatusChanged.class)
            .forEach(type -> BY_NAME.put(type.getSimpleName(), type));
    }

    private EventTypes() {}

    public static String nameOf(Class<?> type) {
        return type.getSimpleName();
    }

    public static Class<?> classFor(String name) {
        Class<?> type = BY_NAME.get(name);
        if (type == null) throw new IllegalArgumentException("Unknown event type " + name);
        return type;
    }
}
