package mx.surtido.orders;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The order lifecycle. Every status change in the service goes through
 * {@link #canMoveTo}, so an event that arrives late or twice cannot move an
 * order backwards (a delivered order never becomes "awaiting payment").
 */
public enum OrderStatus {
    PENDING_STOCK,
    AWAITING_PAYMENT,
    CONFIRMED,
    PREPARING,
    IN_TRANSIT,
    DELIVERED,
    REJECTED,
    EXPIRED,
    CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> NEXT = Map.of(
            PENDING_STOCK, EnumSet.of(AWAITING_PAYMENT, REJECTED, CANCELLED),
            AWAITING_PAYMENT, EnumSet.of(CONFIRMED, EXPIRED, CANCELLED),
            CONFIRMED, EnumSet.of(PREPARING),
            PREPARING, EnumSet.of(IN_TRANSIT),
            IN_TRANSIT, EnumSet.of(DELIVERED),
            DELIVERED, EnumSet.noneOf(OrderStatus.class),
            REJECTED, EnumSet.noneOf(OrderStatus.class),
            EXPIRED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    public boolean canMoveTo(OrderStatus next) {
        return NEXT.get(this).contains(next);
    }

    /** The order will not move again. */
    public boolean isFinal() {
        return NEXT.get(this).isEmpty();
    }

    /** Money taken for an order in one of these states has to go back. */
    public boolean refundsLatePayments() {
        return this == EXPIRED || this == CANCELLED;
    }
}
