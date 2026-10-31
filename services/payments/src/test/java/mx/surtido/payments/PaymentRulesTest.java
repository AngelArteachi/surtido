package mx.surtido.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class PaymentRulesTest {

    @Test
    void computesTheClabeCheckDigit() {
        // Worked example from Banxico's CLABE specification.
        assertThat(Clabe.checkDigit("03218000011835971")).isEqualTo(9);
        assertThat(Clabe.isValid("032180000118359719")).isTrue();
        assertThat(Clabe.isValid("032180000118359710")).isFalse();
    }

    @Test
    void generatesValidClabesForAnyAccount() {
        for (long account = 0; account < 2_000; account += 37) {
            String clabe = Clabe.of("646", "180", String.format("%011d", account * 7919));
            assertThat(clabe).hasSize(18).startsWith("646180");
            assertThat(Clabe.isValid(clabe)).isTrue();
        }
    }

    @Test
    void validatesCardNumbersAndExpiry() {
        assertThat(Cards.luhnValid(Cards.APPROVED)).isTrue();
        assertThat(Cards.luhnValid("4242424242424241")).isFalse();
        YearMonth october = YearMonth.of(2026, 10);
        assertThat(Cards.expired(10, 2026, october)).isFalse();
        assertThat(Cards.expired(9, 26, october)).isTrue();
    }

    @Test
    void declinesOnlyTheSandboxDeclineNumbers() {
        assertThat(Cards.declineReason(Cards.APPROVED)).isNull();
        assertThat(Cards.declineReason(Cards.DECLINED)).contains("rechaz");
        assertThat(Cards.declineReason(Cards.INSUFFICIENT_FUNDS)).contains("fondos");
    }
}
