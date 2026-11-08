package com.medirecords.importjob;

import com.medirecords.patient.PatientBulkRepository;
import com.medirecords.patient.PatientDetails;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * Writes one chunk in its transaction: rejects rows whose email already belongs to a patient (one
 * query for the whole chunk), inserts the rest with a JDBC batch, and records the rejected rows.
 */
class ImportChunkWriter implements ItemWriter<ImportRow> {

    private final long importId;
    private final PatientBulkRepository patients;
    private final ImportProgressRecorder progress;

    ImportChunkWriter(long importId, PatientBulkRepository patients, ImportProgressRecorder progress) {
        this.importId = importId;
        this.patients = patients;
        this.progress = progress;
    }

    @Override
    public void write(Chunk<? extends ImportRow> chunk) {
        List<ImportRow.Valid> candidates = new ArrayList<>();
        List<ImportRow.Invalid> invalid = new ArrayList<>();
        for (ImportRow row : chunk) {
            switch (row) {
                case ImportRow.Valid valid -> candidates.add(valid);
                case ImportRow.Invalid rejected -> invalid.add(rejected);
            }
        }

        Set<String> existing = patients.findExistingEmails(
                candidates.stream().map(row -> row.patient().email()).toList());
        List<PatientDetails> toInsert = new ArrayList<>();
        for (ImportRow.Valid row : candidates) {
            if (existing.contains(row.patient().email())) {
                invalid.add(new ImportRow.Invalid(row.rowNumber(), row.rawLine(),
                        "Duplicate email " + row.patient().email() + " (a patient with this email already exists)"));
            } else {
                toInsert.add(row.patient());
            }
        }

        patients.insertAll(toInsert);
        progress.record(importId, toInsert.size(), invalid);
    }
}
