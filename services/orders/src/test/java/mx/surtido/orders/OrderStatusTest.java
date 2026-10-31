package mx.surtido.orders;

import static mx.surtido.orders.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

    @Test
    void followsTheHappyPathInOrder() {
        assertThat(PENDING_STOCK.canMoveTo(AWAITING_PAYMENT)).isTrue();
        assertThat(AWAITING_PAYMENT.canMoveTo(CONFIRMED)).isTrue();
        assertThat(CONFIRMED.canMoveTo(PREPARING)).isTrue();
        assertThat(PREPARING.canMoveTo(IN_TRANSIT)).isTrue();
        assertThat(IN_TRANSIT.canMoveTo(DELIVERED)).isTrue();
    }

    @Test
    void neverMovesBackwardsOrSkipsPayment() {
        assertThat(DELIVERED.canMoveTo(AWAITING_PAYMENT)).isFalse();
        assertThat(CONFIRMED.canMoveTo(AWAITING_PAYMENT)).isFalse();
        assertThat(PENDING_STOCK.canMoveTo(CONFIRMED)).isFalse();
        assertThat(AWAITING_PAYMENT.canMoveTo(PREPARING)).isFalse();
    }

    @Test
    void cannotCancelOnceConfirmed() {
        assertThat(PENDING_STOCK.canMoveTo(CANCELLED)).isTrue();
        assertThat(AWAITING_PAYMENT.canMoveTo(CANCELLED)).isTrue();
        assertThat(CONFIRMED.canMoveTo(CANCELLED)).isFalse();
    }

    @Test
    void finalStatesStayFinalAndOnlyDeadOrdersRefund() {
        for (OrderStatus status : new OrderStatus[] {DELIVERED, REJECTED, EXPIRED, CANCELLED}) {
            assertThat(status.isFinal()).isTrue();
        }
        assertThat(EXPIRED.refundsLatePayments()).isTrue();
        assertThat(CANCELLED.refundsLatePayments()).isTrue();
        assertThat(CONFIRMED.refundsLatePayments()).isFalse();
    }
}
