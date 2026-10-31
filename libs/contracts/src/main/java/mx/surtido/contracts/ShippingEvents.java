package mx.surtido.contracts;

import java.util.UUID;

public final class ShippingEvents {
    private ShippingEvents() {}

    public record ShipmentStatusChanged(
            UUID orderId, UUID shipmentId, String status, OrderEvents.Store store, String driver) {}
}
