package com.medirecords.importjob;

import com.medirecords.audit.AuditService;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Audits an import as a <em>single</em> entry once it has reached its final state, with the counts
 * instead of one entry per imported patient (a 5,000-row file would otherwise bury every other change).
 * The individual rejected rows are already kept in {@code patient_import_error} and the error report.
 *
 * <p>Called from every place an import finishes: the end of the job, a launch that never started, and
 * the start-up sweep of imports cut off by a restart. Each import reaches a final state exactly once.
 */
@Component
class ImportAudit {

    private final AuditService audit;

    ImportAudit(AuditService audit) {
        this.audit = audit;
    }

    /** Must run in the transaction that marks the import finished. */
    void recordFinished(PatientImport patientImport) {
        if (!patientImport.getStatus().isFinished()) {
            throw new IllegalStateException("Import " + patientImport.getId() + " has not finished");
        }
        int valid = patientImport.getValidRows();
        int invalid = patientImport.getInvalidRows();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fileName", patientImport.getFileName());
        details.put("status", patientImport.getStatus().name());
        details.put("expectedRows", patientImport.getExpectedRows());
        details.put("totalRows", valid + invalid);
        details.put("validRows", valid);
        details.put("invalidRows", invalid);
        if (patientImport.getFailureMessage() != null) {
            details.put("failureMessage", patientImport.getFailureMessage());
        }

        NumberFormat number = NumberFormat.getIntegerInstance(Locale.ENGLISH);
        String outcome = patientImport.getStatus() == ImportStatus.COMPLETED ? "Imported " : "Import failed: ";
        String summary = outcome + patientImport.getFileName() + " (" + number.format(valid) + " valid, "
                + number.format(invalid) + " invalid of " + number.format(valid + invalid) + " rows)";
        audit.recordImport(patientImport.getImportedBy(), patientImport.getId(), summary, details);
    }
}
