package mx.surtido.payments;

/**
 * CLABE, the 18-digit account number every Mexican bank transfer (SPEI) goes
 * to: bank (3) + branch plaza (3) + account (11) + check digit (1).
 */
public final class Clabe {
    private static final int[] WEIGHTS = {3, 7, 1};

    private Clabe() {}

    /** The check digit: each of the first 17 digits times 3, 7, 1, 3, 7, 1..., each product mod 10, then 10 minus the sum mod 10. */
    public static int checkDigit(String first17) {
        if (!first17.matches("\\d{17}")) throw new IllegalArgumentException("A CLABE starts with 17 digits");
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (Character.digit(first17.charAt(i), 10) * WEIGHTS[i % 3]) % 10;
        }
        return (10 - sum % 10) % 10;
    }

    public static String of(String bank, String plaza, String account) {
        String first17 = bank + plaza + account;
        return first17 + checkDigit(first17);
    }

    public static boolean isValid(String clabe) {
        return clabe != null && clabe.matches("\\d{18}") && checkDigit(clabe.substring(0, 17)) == Character.digit(clabe.charAt(17), 10);
    }
}
