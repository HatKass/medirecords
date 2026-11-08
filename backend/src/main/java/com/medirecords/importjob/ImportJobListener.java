package com.medirecords.importjob;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Mirrors the Batch job's lifecycle onto the {@link PatientImport} row the UI polls. */
@Component
class ImportJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ImportJobListener.class);

    private final PatientImportRepository imports;
    private final ImportFileStorage storage;
    private final Clock clock;

    ImportJobListener(PatientImportRepository imports, ImportFileStorage storage, Clock clock) {
        this.imports = imports;
        this.storage = storage;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void beforeJob(JobExecution execution) {
        find(execution).markRunning(execution.getId(), clock.instant());
    }

    @Override
    @Transactional
    public void afterJob(JobExecution execution) {
        PatientImport patientImport = find(execution);
        if (execution.getStatus() == BatchStatus.COMPLETED) {
            patientImport.markCompleted(clock.instant());
        } else {
            patientImport.markFailed(failureMessage(execution.getAllFailureExceptions()), clock.instant());
        }
        log.info("Import {} ({}) finished: {}, {} valid, {} invalid rows", patientImport.getId(),
                patientImport.getFileName(), patientImport.getStatus(), patientImport.getValidRows(),
                patientImport.getInvalidRows());
        storage.delete(Path.of(execution.getJobParameters().getString(ImportJobConfig.PARAM_FILE)));
    }

    private PatientImport find(JobExecution execution) {
        long id = execution.getJobParameters().getLong(ImportJobConfig.PARAM_IMPORT_ID);
        return imports.findById(id).orElseThrow(() -> new IllegalStateException("Import " + id + " not found"));
    }

    private static String failureMessage(List<Throwable> failures) {
        if (failures.isEmpty()) {
            return "The import was stopped before it finished.";
        }
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failures.getFirst());
        log.error("Import job failed", failures.getFirst());
        return "The import stopped early: " + cause.getMessage()
                + ". Rows imported before this point were kept.";
    }
}
