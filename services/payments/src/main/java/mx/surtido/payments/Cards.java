package mx.surtido.payments;

import java.time.YearMonth;

/** The simulated card network. Test numbers follow the convention of payment sandboxes. */
public final class Cards {
    public static final String APPROVED = "4242424242424242";
    public static final String DECLINED = "4000000000000002";
    public static final String INSUFFICIENT_FUNDS = "4000000000009995";

    private Cards() {}

    public static boolean luhnValid(String number) {
        if (number == null || !number.matches("\\d{12,19}")) return false;
        int sum = 0;
        boolean twice = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = Character.digit(number.charAt(i), 10);
            if (twice) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
            twice = !twice;
        }
        return sum % 10 == 0;
    }

    /** A card is good through the last day of its expiry month. */
    public static boolean expired(int month, int year, YearMonth now) {
        int fullYear = year < 100 ? 2000 + year : year;
        return YearMonth.of(fullYear, month).isBefore(now);
    }

    public static String brand(String number) {
        if (number.startsWith("4")) return "visa";
        if (number.matches("^5[1-5].*") || number.matches("^2[2-7].*")) return "mastercard";
        if (number.matches("^3[47].*")) return "amex";
        return "card";
    }

    /** Null when the charge goes through, otherwise the decline reason shown to the store. */
    public static String declineReason(String number) {
        return switch (number) {
            case DECLINED -> "el banco rechazó la tarjeta";
            case INSUFFICIENT_FUNDS -> "fondos insuficientes";
            default -> null;
        };
    }
}
