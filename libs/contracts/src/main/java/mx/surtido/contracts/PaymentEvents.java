package mx.surtido.contracts;

import java.util.UUID;

public final class PaymentEvents {
    private PaymentEvents() {}

    public record PaymentCompleted(UUID orderId, UUID paymentId, long amountCents, String method) {}

    /** A declined card. The order keeps waiting, so the store can try again until the window closes. */
    public record PaymentFailed(UUID orderId, UUID paymentId, String reason) {}
}
