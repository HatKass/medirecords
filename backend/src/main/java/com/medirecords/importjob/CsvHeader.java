package com.medirecords.importjob;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;

/**
 * Maps a CSV header to the import's columns. Real exports differ in naming and order ("First Name",
 * "first_name", "DOB", "Sex"...), so headers are matched by normalized name and alias, in any order.
 * Unrecognized columns are ignored, which is also what makes the error report re-importable: its
 * extra "row" and "error" columns are simply skipped.
 */
public final class CsvHeader {

    public static final String FIRST_NAME = "firstName";
    public static final String LAST_NAME = "lastName";
    public static final String DATE_OF_BIRTH = "dateOfBirth";
    public static final String GENDER = "gender";
    public static final String EMAIL = "email";
    public static final String PHONE = "phone";
    public static final String CITY = "city";
    public static final String BLOOD_TYPE = "bloodType";

    public static final List<String> REQUIRED = List.of(FIRST_NAME, LAST_NAME, DATE_OF_BIRTH, GENDER, EMAIL, CITY);
    public static final List<String> OPTIONAL = List.of(PHONE, BLOOD_TYPE);

    /** Normalized header text (lower-case letters and digits only) to column. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("firstname", FIRST_NAME), Map.entry("givenname", FIRST_NAME), Map.entry("forename", FIRST_NAME),
            Map.entry("lastname", LAST_NAME), Map.entry("surname", LAST_NAME), Map.entry("familyname", LAST_NAME),
            Map.entry("dateofbirth", DATE_OF_BIRTH), Map.entry("dob", DATE_OF_BIRTH),
            Map.entry("birthdate", DATE_OF_BIRTH), Map.entry("birthday", DATE_OF_BIRTH),
            Map.entry("gender", GENDER), Map.entry("sex", GENDER),
            Map.entry("email", EMAIL), Map.entry("emailaddress", EMAIL), Map.entry("mail", EMAIL),
            Map.entry("phone", PHONE), Map.entry("phonenumber", PHONE), Map.entry("mobile", PHONE),
            Map.entry("telephone", PHONE), Map.entry("tel", PHONE),
            Map.entry("city", CITY), Map.entry("town", CITY),
            Map.entry("bloodtype", BLOOD_TYPE), Map.entry("bloodgroup", BLOOD_TYPE), Map.entry("blood", BLOOD_TYPE));

    private static final String IGNORED_PREFIX = "ignored";

    private final String line;
    private final String[] names;
    private final List<String> columns;
    private final List<String> missing;

    private CsvHeader(String line, String[] names, List<String> columns, List<String> missing) {
        this.line = line;
        this.names = names;
        this.columns = columns;
        this.missing = missing;
    }

    /** Parses a header line (a leading UTF-8 byte order mark, as Excel writes it, is ignored). */
    public static CsvHeader parse(String headerLine) {
        String line = headerLine.startsWith("﻿") ? headerLine.substring(1) : headerLine;
        String[] raw = newTokenizer().tokenize(line).getValues();
        String[] names = new String[raw.length];
        Set<String> found = new LinkedHashSet<>();
        for (int i = 0; i < raw.length; i++) {
            String column = ALIASES.get(normalize(raw[i]));
            // A column listed twice is read from its first occurrence only.
            names[i] = column != null && found.add(column) ? column : IGNORED_PREFIX + i;
        }
        List<String> missing = new ArrayList<>();
        for (String column : REQUIRED) {
            if (!found.contains(column)) {
                missing.add(column);
            }
        }
        return new CsvHeader(line, names, List.copyOf(found), List.copyOf(missing));
    }

    /** Comma-separated, double-quote-enclosed fields (RFC 4180). */
    static DelimitedLineTokenizer newTokenizer() {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer(DelimitedLineTokenizer.DELIMITER_COMMA);
        tokenizer.setQuoteCharacter(DelimitedLineTokenizer.DEFAULT_QUOTE_CHARACTER);
        tokenizer.setStrict(true); // a row with too few or too many fields is an error, not silently padded
        return tokenizer;
    }

    private static String normalize(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The header as uploaded (without BOM), reused as the error report's header. */
    public String line() {
        return line;
    }

    /** One name per CSV field: a column constant, or a placeholder for ignored fields. */
    public String[] names() {
        return names.clone();
    }

    /** The recognized columns present in this file. */
    public List<String> columns() {
        return columns;
    }

    public boolean isValid() {
        return missing.isEmpty();
    }

    public List<String> missingColumns() {
        return missing;
    }
}
