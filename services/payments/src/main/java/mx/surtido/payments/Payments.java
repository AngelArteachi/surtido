package mx.surtido.payments;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import mx.surtido.contracts.OrderEvents.PaymentRequested;
import mx.surtido.contracts.PaymentEvents.PaymentCompleted;
import mx.surtido.contracts.PaymentEvents.PaymentFailed;
import mx.surtido.contracts.Topics;
import mx.surtido.messaging.Inbox;
import mx.surtido.messaging.Outbox;

@Service
public class Payments {
    /** STP's bank code and Mexico City's plaza: where a distributor's collection account typically lives. */
    private static final String BANK = "646";
    private static final String PLAZA = "180";

    public record Payment(
            UUID id, UUID orderId, UUID storeId, long amountCents, String method, String status,
            String clabe, String reference, String cardBrand, String cardLast4, int failedAttempts, Instant expiresAt) {}

    public record Card(String number, int expMonth, int expYear, String cvc, String holderName) {}

    private static final String COLUMNS = """
            id, order_id, store_id, amount_cents, method, status, clabe, reference, card_brand, card_last4,
            failed_attempts, expires_at""";

    private final JdbcClient db;
    private final Inbox inbox;
    private final Outbox outbox;
    private final MeterRegistry metrics;

    public Payments(JdbcClient db, Inbox inbox, Outbox outbox, MeterRegistry metrics) {
        this.db = db;
        this.inbox = inbox;
        this.outbox = outbox;
        this.metrics = metrics;
    }

    private static Payment map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Payment(
                rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getLong("amount_cents"), rs.getString("method"), rs.getString("status"), rs.getString("clabe"),
                rs.getString("reference"), rs.getString("card_brand"), rs.getString("card_last4"),
                rs.getInt("failed_attempts"), rs.getTimestamp("expires_at").toInstant());
    }

    public Optional<Payment> forOrder(UUID orderId) {
        return db.sql("SELECT " + COLUMNS + " FROM payments WHERE order_id = ?").param(orderId).query((rs, i) -> map(rs)).optional();
    }

    public List<Payment> pendingSpei() {
        return db.sql("SELECT " + COLUMNS + " FROM payments WHERE method = 'SPEI' AND status = 'REQUIRES_PAYMENT' ORDER BY created_at")
                .query((rs, i) -> map(rs))
                .list();
    }

    private Payment lockForOrder(UUID orderId) {
        return db.sql("SELECT " + COLUMNS + " FROM payments WHERE order_id = ? FOR UPDATE")
                .param(orderId)
                .query((rs, i) -> map(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No payment for this order yet"));
    }

    @Transactional
    public void open(UUID messageId, PaymentRequested request) {
        if (!inbox.firstDelivery(messageId)) return;
        String clabe = null;
        String reference = null;
        if (request.method().equals("SPEI")) {
            // A per-order account number and a 7-digit reference, so a deposit identifies its order.
            String account = String.format("%011d", Math.floorMod(request.orderId().getMostSignificantBits(), 100_000_000_000L));
            clabe = Clabe.of(BANK, PLAZA, account);
            reference = String.format("%07d", ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000));
        }
        db.sql("""
                INSERT INTO payments (order_id, store_id, amount_cents, method, status, clabe, reference, expires_at)
                VALUES (?, ?, ?, ?, 'REQUIRES_PAYMENT', ?, ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """)
                .params(request.orderId(), request.storeId(), request.amountCents(), request.method(), clabe, reference,
                        OffsetDateTime.ofInstant(request.expiresAt(), ZoneOffset.UTC))
                .update();
    }

    public sealed interface ChargeResult {
        record Approved(Payment payment) implements ChargeResult {}

        record Declined(String reason) implements ChargeResult {}
    }

    @Transactional
    public ChargeResult chargeCard(UUID orderId, Card card) {
        String number = card.number().replaceAll("[\\s-]", "");
        if (!Cards.luhnValid(number)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Card number is not valid");
        if (card.expMonth() < 1 || card.expMonth() > 12 || Cards.expired(card.expMonth(), card.expYear(), YearMonth.now(ZoneOffset.UTC))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The card has expired");
        }

        Payment payment = lockForOrder(orderId);
        if (!payment.method().equals("CARD")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is paid by SPEI transfer");
        if (!payment.status().equals("REQUIRES_PAYMENT")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment is already " + payment.status().toLowerCase());
        }

        String decline = Cards.declineReason(number);
        if (decline != null) {
            db.sql("UPDATE payments SET failed_attempts = failed_attempts + 1, updated_at = now() WHERE id = ?").param(payment.id()).update();
            outbox.publish(Topics.PAYMENTS, orderId, new PaymentFailed(orderId, payment.id(), decline));
            metrics.counter("payments.card", "result", "declined").increment();
            return new ChargeResult.Declined(decline);
        }

        db.sql("""
                UPDATE payments SET status = 'SUCCEEDED', card_brand = ?, card_last4 = ?, updated_at = now() WHERE id = ?
                """)
                .params(Cards.brand(number), number.substring(number.length() - 4), payment.id())
                .update();
        outbox.publish(Topics.PAYMENTS, orderId, new PaymentCompleted(orderId, payment.id(), payment.amountCents(), "CARD"));
        metrics.counter("payments.card", "result", "approved").increment();
        return new ChargeResult.Approved(lockForOrder(orderId));
    }

    /**
     * Simulates the bank notifying a SPEI deposit. A deposit is matched by
     * reference and must be for the exact amount. Money that arrives after the
     * order expired is still recorded: the saga then asks for a refund.
     */
    @Transactional
    public Payment speiDeposit(String reference, long amountCents) {
        Payment payment = db.sql("SELECT " + COLUMNS + " FROM payments WHERE reference = ? FOR UPDATE")
                .param(reference)
                .query((rs, i) -> map(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No payment with that reference"));
        if (payment.amountCents() != amountCents) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The deposit does not match the amount due");
        }
        if (!payment.status().equals("REQUIRES_PAYMENT") && !payment.status().equals("CANCELLED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This payment is already " + payment.status().toLowerCase());
        }
        db.sql("UPDATE payments SET status = 'SUCCEEDED', updated_at = now() WHERE id = ?").param(payment.id()).update();
        outbox.publish(Topics.PAYMENTS, payment.orderId(),
                new PaymentCompleted(payment.orderId(), payment.id(), payment.amountCents(), "SPEI"));
        metrics.counter("payments.spei", "result", payment.status().equals("CANCELLED") ? "late" : "on_time").increment();
        return lockForOrder(payment.orderId());
    }

    /** The order expired or was cancelled: stop accepting card payments for it. */
    @Transactional
    public void cancel(UUID messageId, UUID orderId) {
        if (!inbox.firstDelivery(messageId)) return;
        db.sql("UPDATE payments SET status = 'CANCELLED', updated_at = now() WHERE order_id = ? AND status = 'REQUIRES_PAYMENT'")
                .param(orderId)
                .update();
    }

    @Transactional
    public void refund(UUID messageId, UUID orderId) {
        if (!inbox.firstDelivery(messageId)) return;
        int changed = db.sql("UPDATE payments SET status = 'REFUNDED', updated_at = now() WHERE order_id = ? AND status = 'SUCCEEDED'")
                .param(orderId)
                .update();
        if (changed > 0) metrics.counter("payments.refunds").increment();
    }
}
