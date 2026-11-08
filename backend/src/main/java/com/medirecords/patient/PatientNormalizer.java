package com.medirecords.patient;

import java.util.Locale;

/**
 * Cleans free-text input so that equal data is stored equally. Used for API writes and, in
 * Phase 4, for the CSV import, so a patient looks the same however it entered the system.
 */
public final class PatientNormalizer {

    private PatientNormalizer() {
    }

    /** Trims and collapses inner whitespace; keeps the original casing ("de Vries", "O'Brien"). */
    public static String text(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Emails compare case-insensitively in practice, so they are stored lower-case. */
    public static String email(String value) {
        String cleaned = text(value);
        return cleaned == null ? null : cleaned.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Keeps digits and a leading "+", dropping spaces, dots, dashes and brackets:
     * "+44 7700 900-123" becomes "+447700900123". A "00" international prefix becomes "+".
     */
    public static String phone(String value) {
        String cleaned = text(value);
        if (cleaned == null) {
            return null;
        }
        boolean international = cleaned.startsWith("+");
        String digits = cleaned.replaceAll("\\D", "");
        if (!international && digits.startsWith("00")) {
            international = true;
            digits = digits.substring(2);
        }
        if (digits.isEmpty()) {
            return null;
        }
        return international ? "+" + digits : digits;
    }
}
