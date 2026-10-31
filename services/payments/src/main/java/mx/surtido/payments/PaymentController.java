package mx.surtido.payments;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PaymentController {
    private final Payments payments;

    public PaymentController(Payments payments) {
        this.payments = payments;
    }

    @GetMapping("/payments/order/{orderId}")
    public Payments.Payment forOrder(@PathVariable UUID orderId) {
        return payments.forOrder(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No payment for this order yet"));
    }

    public record CardRequest(
            @NotBlank String number,
            int expMonth,
            int expYear,
            @Pattern(regexp = "\\d{3,4}") String cvc,
            @NotBlank String holderName) {}

    public record ChargeResponse(String result, String reason, Payments.Payment payment) {}

    /** The store pays by card. A decline is a normal answer (200), not an error: the store can try another card. */
    @PostMapping("/payments/order/{orderId}/card")
    public ChargeResponse payByCard(@PathVariable UUID orderId, @Valid @RequestBody CardRequest body) {
        var result = payments.chargeCard(orderId,
                new Payments.Card(body.number(), body.expMonth(), body.expYear(), body.cvc(), body.holderName()));
        return switch (result) {
            case Payments.ChargeResult.Approved approved -> new ChargeResponse("approved", null, approved.payment());
            case Payments.ChargeResult.Declined declined -> new ChargeResponse("declined", declined.reason(), null);
        };
    }

    @GetMapping("/payments/spei/pending")
    public List<Payments.Payment> pendingSpei() {
        return payments.pendingSpei();
    }

    public record Deposit(@Pattern(regexp = "\\d{7}") String reference, @Positive long amountCents) {}

    /** Stands in for the bank's deposit notification, so the warehouse panel can simulate a transfer. */
    @PostMapping("/payments/spei/deposits")
    public ResponseEntity<Payments.Payment> deposit(@Valid @RequestBody Deposit body) {
        return ResponseEntity.ok(payments.speiDeposit(body.reference(), body.amountCents()));
    }
}
