package com.medirecords.importjob;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.batch.item.file.LineCallbackHandler;
import org.springframework.batch.item.file.LineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.file.transform.FieldSet;

/**
 * Turns a line into a {@link CsvRow}. It is also the reader's header callback: the column names are
 * taken from the file's own header, so columns may come in any order.
 *
 * <p>Lines with the wrong number of fields make the tokenizer throw; the reader wraps that in a
 * {@code FlatFileParseException}, which the step skips and records as an invalid row.
 */
class CsvRowLineMapper implements LineMapper<CsvRow>, LineCallbackHandler {

    private final DelimitedLineTokenizer tokenizer = CsvHeader.newTokenizer();
    private List<String> columns;

    @Override
    public void handleLine(String headerLine) {
        CsvHeader header = CsvHeader.parse(headerLine);
        if (!header.isValid()) {
            // Checked at upload already; this guards against the file changing on disk in between.
            throw new IllegalStateException("Missing required columns: " + String.join(", ", header.missingColumns()));
        }
        tokenizer.setNames(header.names());
        columns = header.columns();
    }

    @Override
    public CsvRow mapLine(String line, int lineNumber) {
        if (columns == null) {
            throw new IllegalStateException("Header line has not been read");
        }
        if (line.isBlank()) {
            return new CsvRow(lineNumber, line, Map.of());
        }
        FieldSet fields = tokenizer.tokenize(line);
        Map<String, String> values = new HashMap<>();
        for (String column : columns) {
            values.put(column, fields.readRawString(column));
        }
        return new CsvRow(lineNumber, line, values);
    }
}
