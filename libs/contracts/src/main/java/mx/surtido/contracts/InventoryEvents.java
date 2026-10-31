package mx.surtido.contracts;

import java.util.List;
import java.util.UUID;

/** Published by the catalog service in reply to the orders saga. */
public final class InventoryEvents {
    private InventoryEvents() {}

    /** Prices are taken from the catalog at reservation time; the client never sets them. */
    public record StockReserved(UUID orderId, List<OrderEvents.PricedLine> lines, long totalCents) {}

    public record StockRejected(UUID orderId, UUID productId, String productName, int requested, int available) {}

    public record StockReleased(UUID orderId) {}
}
