package it.uni.pdta.pdta_camunda.registration;

import java.util.regex.Pattern;

/** Validazione formale del codice fiscale persona fisica (16 caratteri, con omocodia e carattere di controllo). */
public final class TaxCode {
    private static final Pattern FORMAT = Pattern.compile("[A-Z]{6}[0-9LMNPQRSTUV]{2}[ABCDEHLMPRST][0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{3}[A-Z]");
    private static final int[] ODD = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23};

    private TaxCode() { }

    public static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public static boolean isValid(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) return false;
        int sum = 0;
        for (int i = 0; i < 15; i++) {
            char c = value.charAt(i);
            int index = Character.isDigit(c) ? c - '0' : c - 'A';
            sum += i % 2 == 0 ? ODD[index] : index;
        }
        return value.charAt(15) == (char) ('A' + sum % 26);
    }
}
