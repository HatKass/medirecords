package com.medirecords.importjob;

import com.medirecords.patient.BloodType;
import com.medirecords.patient.Gender;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Lenient parsing of the values real-world CSV exports contain. Each method returns empty when the
 * text cannot be understood, and the caller turns that into a row error.
 */
final class CsvValues {

    /**
     * Accepted date formats. Numeric dates with slashes, dots or dashes are read day-first
     * (31/12/1980), the convention of the clinic's (UK) locale; ISO dates (1980-12-31) are unambiguous.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            strict("uuuu-MM-dd"),
            strict("uuuu/MM/dd"),
            strict("uuuuMMdd"),
            strict("d/M/uuuu"),
            strict("d.M.uuuu"),
            strict("d-M-uuuu"),
            strict("d MMM uuuu"),
            strict("d MMMM uuuu"),
            strict("MMM d, uuuu"),
            strict("MMMM d, uuuu"));

    private static final Map<String, Gender> GENDERS = Map.of(
            "f", Gender.FEMALE, "female", Gender.FEMALE, "w", Gender.FEMALE, "woman", Gender.FEMALE,
            "m", Gender.MALE, "male", Gender.MALE, "man", Gender.MALE,
            "o", Gender.OTHER, "other", Gender.OTHER, "x", Gender.OTHER);

    private CsvValues() {
    }

    static Optional<LocalDate> date(String text) {
        // "1980-12-31T00:00:00" or "1980-12-31 00:00" from spreadsheet exports: keep the date part.
        String value = text.strip().replaceFirst("^(\\d{4}-\\d{2}-\\d{2})[T ].*$", "$1");
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(value, format));
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return Optional.empty();
    }

    static Optional<Gender> gender(String text) {
        String key = text.strip().toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
        if (key.equals("nonbinary")) {
            return Optional.of(Gender.OTHER);
        }
        return Optional.ofNullable(GENDERS.get(key));
    }

    /** "A+", "a pos", "AB negative", "O-" (also with a zero), "A_POSITIVE"... */
    static Optional<BloodType> bloodType(String text) {
        String value = text.strip().toUpperCase(Locale.ROOT).replace('0', 'O').replace('−', '-').replaceAll("[\\s_]", "");
        String group;
        String rh;
        if (value.endsWith("+") || value.endsWith("-")) {
            group = value.substring(0, value.length() - 1);
            rh = value.endsWith("+") ? "POSITIVE" : "NEGATIVE";
        } else if (value.endsWith("POSITIVE") || value.endsWith("NEGATIVE")) {
            group = value.substring(0, value.length() - 8);
            rh = value.substring(value.length() - 8);
        } else if (value.endsWith("POS") || value.endsWith("NEG")) {
            group = value.substring(0, value.length() - 3);
            rh = value.endsWith("POS") ? "POSITIVE" : "NEGATIVE";
        } else {
            return Optional.empty();
        }
        if (!List.of("A", "B", "AB", "O").contains(group)) {
            return Optional.empty();
        }
        return Optional.of(BloodType.valueOf(group + "_" + rh));
    }

    private static DateTimeFormatter strict(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT); // 31/02/1990 is rejected, not rolled over
    }
}
