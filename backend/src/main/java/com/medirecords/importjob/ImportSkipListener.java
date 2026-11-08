package com.medirecords.importjob;

import java.util.List;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.transform.IncorrectTokenCountException;

/**
 * Records lines the reader could not even split into fields (wrong number of columns, broken
 * quoting). Spring Batch calls this inside the chunk transaction, just before it commits.
 */
class ImportSkipListener implements SkipListener<CsvRow, ImportRow> {

    private final long importId;
    private final ImportProgressRecorder progress;

    ImportSkipListener(long importId, ImportProgressRecorder progress) {
        this.importId = importId;
        this.progress = progress;
    }

    @Override
    public void onSkipInRead(Throwable failure) {
        if (failure instanceof FlatFileParseException parse) {
            progress.record(importId, 0,
                    List.of(new ImportRow.Invalid(parse.getLineNumber(), parse.getInput(), describe(parse))));
        }
    }

    /**
     * Safety net: a row passed validation and the duplicate check but the database still refused it,
     * typically because another request inserted the same email a moment earlier. Spring Batch has
     * already retried the chunk row by row, so only this row is rejected.
     */
    @Override
    public void onSkipInWrite(ImportRow row, Throwable failure) {
        progress.record(importId, 0, List.of(new ImportRow.Invalid(row.rowNumber(), row.rawLine(),
                "Rejected by the database (conflicts with existing data, e.g. a duplicate email)")));
    }

    private static String describe(FlatFileParseException failure) {
        if (failure.getCause() instanceof IncorrectTokenCountException count) {
            return "Wrong number of columns: expected " + count.getExpectedCount() + ", found " + count.getActualCount();
        }
        return "Line could not be parsed as CSV (check quotes and separators)";
    }
}
