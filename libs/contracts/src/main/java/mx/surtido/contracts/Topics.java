package mx.surtido.contracts;

/** Kafka topics. Every message is keyed by order id, so events for one order stay in order. */
public final class Topics {
    public static final String ORDERS = "orders.events";
    public static final String INVENTORY = "inventory.events";
    public static final String PAYMENTS = "payments.events";
    public static final String SHIPPING = "shipping.events";

    private Topics() {}
}
