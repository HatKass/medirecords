package com.medirecords.importjob;

import java.util.Map;

/**
 * One parsed CSV record before validation: the raw text values of the recognized columns.
 *
 * @param rowNumber line number in the file (header = 1), the row a spreadsheet shows
 * @param rawLine   the line exactly as uploaded, kept for the error report
 * @param values    column name (see {@link CsvHeader}) to raw value; absent optional columns are missing
 */
public record CsvRow(int rowNumber, String rawLine, Map<String, String> values) {

    public CsvRow {
        values = Map.copyOf(values);
    }

    public String get(String column) {
        return values.get(column);
    }

    public boolean isBlank() {
        return rawLine.isBlank();
    }
}
