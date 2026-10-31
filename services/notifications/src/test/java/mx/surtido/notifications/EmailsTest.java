package mx.surtido.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import mx.surtido.contracts.OrderEvents.OrderConfirmed;
import mx.surtido.contracts.OrderEvents.PricedLine;
import mx.surtido.contracts.OrderEvents.Store;

class EmailsTest {
    private final Store store = new Store(UUID.randomUUID(), "Abarrotes <b>La Esperanza</b>", "esperanza@tiendas.test",
            "Calle Reforma 112", "Villahermosa");

    @Test
    void escapesStoreAndProductNames() {
        var email = Emails.confirmed(new OrderConfirmed(UUID.randomUUID(), store, 91_200,
                List.of(new PricedLine(UUID.randomUUID(), "Aceite \"vegetal\" & más", 2, 45_600))));
        assertThat(email.html()).doesNotContain("<b>La Esperanza</b>").contains("&lt;b&gt;La Esperanza");
        assertThat(email.html()).contains("Aceite &quot;vegetal&quot; &amp; más");
        assertThat(email.to()).isEqualTo("esperanza@tiendas.test");
    }

    @Test
    void showsAmountsInPesos() {
        assertThat(Emails.money(91_200)).contains("912.00");
        var email = Emails.confirmed(new OrderConfirmed(UUID.randomUUID(), store, 91_200, List.of()));
        assertThat(email.html()).contains(Emails.money(91_200));
    }
}
