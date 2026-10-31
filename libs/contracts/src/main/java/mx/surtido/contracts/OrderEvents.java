package mx.surtido.contracts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Published by the orders service, which orchestrates the saga. */
public final class OrderEvents {
    private OrderEvents() {}

    public record RequestedLine(UUID productId, int quantity) {}

    public record PricedLine(UUID productId, String name, int quantity, long unitPriceCents) {}

    public record Store(UUID id, String name, String email, String address, String city) {}

    /** Asks inventory to reserve every line, or none. */
    public record OrderPlaced(UUID orderId, UUID storeId, List<RequestedLine> lines) {}

    /** Stock is reserved: payments opens a payment for the order total. */
    public record PaymentRequested(UUID orderId, UUID storeId, long amountCents, String method, Instant expiresAt) {}

    /** Paid in time: inventory commits the reservation and shipping prepares the delivery. */
    public record OrderConfirmed(UUID orderId, Store store, long totalCents, List<PricedLine> lines) {}

    /** Not enough stock for at least one line. */
    public record OrderRejected(UUID orderId, Store store, String reason) {}

    /** The payment window closed: inventory releases the stock, payments stops accepting money. */
    public record OrderExpired(UUID orderId, Store store, long totalCents) {}

    /** The store cancelled before paying. */
    public record OrderCancelled(UUID orderId, UUID storeId) {}

    /** Money arrived for an order that had already expired. */
    public record RefundRequested(UUID orderId, String reason) {}
}
