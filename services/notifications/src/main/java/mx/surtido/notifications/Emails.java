package mx.surtido.notifications;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.web.util.HtmlUtils;
import mx.surtido.contracts.OrderEvents.OrderConfirmed;
import mx.surtido.contracts.OrderEvents.OrderExpired;
import mx.surtido.contracts.OrderEvents.OrderRejected;
import mx.surtido.contracts.OrderEvents.Store;
import mx.surtido.contracts.ShippingEvents.ShipmentStatusChanged;

/** The emails a store receives. Every value from an event is HTML-escaped. */
final class Emails {
    record Email(String to, String subject, String html) {}

    private Emails() {}

    private static final Locale MX = Locale.forLanguageTag("es-MX");

    static String money(long cents) {
        return NumberFormat.getCurrencyInstance(MX).format(cents / 100.0);
    }

    private static String esc(String value) {
        // With UTF-8 only markup characters are escaped; accents stay readable.
        return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
    }

    private static String shortId(Object orderId) {
        return orderId.toString().substring(0, 8).toUpperCase();
    }

    private static String layout(String heading, String body) {
        return """
                <!doctype html><html lang="es"><body style="margin:0;background:#eef1ee;font-family:Arial,Helvetica,sans-serif;color:#17231c">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="padding:28px 12px"><tr><td align="center">
                <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background:#fff;border-radius:12px;overflow:hidden">
                <tr><td style="background:#17231c;color:#f5c400;padding:18px 26px;font-size:20px;font-weight:800;letter-spacing:.06em;text-transform:uppercase">Surtido</td></tr>
                <tr><td style="padding:26px"><h1 style="margin:0 0 14px;font-size:22px;line-height:1.25">%s</h1>%s</td></tr>
                <tr><td style="padding:0 26px 22px;color:#5b6b61;font-size:12px">Correo de un entorno de demostración de Surtido. No se hizo ningún cargo real.</td></tr>
                </table></td></tr></table></body></html>
                """.formatted(heading, body);
    }

    private static String greeting(Store store) {
        return "<p style=\"margin:0 0 12px;color:#5b6b61\">Hola, " + esc(store.name()) + ".</p>";
    }

    static Email confirmed(OrderConfirmed order) {
        String rows = order.lines().stream()
                .map(l -> "<tr><td style=\"padding:6px 0\">" + l.quantity() + " × " + esc(l.name()) + "</td>"
                        + "<td align=\"right\" style=\"padding:6px 0\">" + money(l.unitPriceCents() * l.quantity()) + "</td></tr>")
                .collect(Collectors.joining());
        String body = greeting(order.store())
                + "<p style=\"margin:0 0 16px;line-height:1.6\">Recibimos tu pago y ya estamos preparando tu pedido para entregarlo en "
                + esc(order.store().address()) + ", " + esc(order.store().city()) + ".</p>"
                + "<table role=\"presentation\" width=\"100%\" style=\"border-top:1px solid #dfe5e0;font-size:14px\">" + rows
                + "<tr><td style=\"padding:10px 0;border-top:1px solid #dfe5e0;font-weight:700\">Total</td>"
                + "<td align=\"right\" style=\"padding:10px 0;border-top:1px solid #dfe5e0;font-weight:700\">" + money(order.totalCents()) + "</td></tr></table>";
        return new Email(order.store().email(), "Pedido " + shortId(order.orderId()) + " confirmado", layout("Tu pedido está confirmado", body));
    }

    static Email rejected(OrderRejected order) {
        String body = greeting(order.store())
                + "<p style=\"margin:0;line-height:1.6\">No pudimos surtir tu pedido: " + esc(order.reason())
                + ". No se hizo ningún cargo. Puedes volver a pedir con menos piezas de ese producto.</p>";
        return new Email(order.store().email(), "No pudimos surtir el pedido " + shortId(order.orderId()), layout("Faltó mercancía", body));
    }

    static Email expired(OrderExpired order) {
        String body = greeting(order.store())
                + "<p style=\"margin:0;line-height:1.6\">El pedido de " + money(order.totalCents())
                + " se canceló porque no recibimos el pago a tiempo. La mercancía volvió al almacén. Si ya transferiste, te devolvemos el dinero.</p>";
        return new Email(order.store().email(), "Pedido " + shortId(order.orderId()) + " vencido", layout("Venció el plazo de pago", body));
    }

    static Email shipment(ShipmentStatusChanged change) {
        boolean delivered = change.status().equals("DELIVERED");
        String body = greeting(change.store())
                + "<p style=\"margin:0;line-height:1.6\">"
                + (delivered
                        ? "Entregamos tu pedido. Gracias por surtirte con nosotros."
                        : "Tu pedido salió del almacén con " + esc(change.driver()) + " y va camino a " + esc(change.store().address()) + ".")
                + "</p>";
        return new Email(change.store().email(),
                "Pedido " + shortId(change.orderId()) + (delivered ? " entregado" : " en camino"),
                layout(delivered ? "Pedido entregado" : "Tu pedido va en camino", body));
    }
}
