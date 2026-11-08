package com.medirecords.importjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;

/**
 * The paths of {@link ImportService#start} where the job never starts, which a running server only hits
 * under load (the bounded worker queue is full). Unit test with Mockito: no Spring context, no database.
 */
class ImportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final String CSV = "firstName,lastName,dateOfBirth,gender,email,city\nA,B,1990-01-01,F,a@example.com,York\n";

    @TempDir
    Path storageDir;

    private final PatientImportRepository imports = mock(PatientImportRepository.class);
    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final ImportAudit audit = mock(ImportAudit.class);
    private final AtomicReference<PatientImport> saved = new AtomicReference<>();
    private ImportService service;

    @BeforeEach
    void setUp() {
        when(imports.save(any())).thenAnswer(invocation -> {
            PatientImport patientImport = invocation.getArgument(0);
            ReflectionTestUtils.setField(patientImport, "id", 42L);
            saved.set(patientImport);
            return patientImport;
        });
        when(imports.findById(42L)).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        service = new ImportService(imports, mock(ImportRowErrorRepository.class),
                new ImportFileStorage(new ImportProperties(storageDir, 500, 2)), jobLauncher, mock(Job.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)), audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aFullWorkerQueueAnswers503AndRecordsTheImportAsFailed() throws Exception {
        // TaskExecutorJobLauncher's way of saying "rejected by the executor": FAILED, never started.
        JobExecution rejected = new JobExecution(1L);
        rejected.setStatus(BatchStatus.FAILED);
        when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenReturn(rejected);

        assertServiceUnavailable();
    }

    @Test
    void aLauncherExceptionAnswers503AndRecordsTheImportAsFailed() throws Exception {
        when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenThrow(new JobRestartException("boom"));

        assertServiceUnavailable();
    }

    @Test
    void anInvalidFileIsNotKeptOnDisk() throws IOException {
        MockMultipartFile noRows = new MockMultipartFile("file", "p.csv", "text/csv",
                "firstName,lastName,dateOfBirth,gender,email,city\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.start(noRows, "admin")).hasMessageContaining("no data rows");

        verify(imports, never()).save(any());
        assertStorageEmpty();
    }

    private void assertServiceUnavailable() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "patients.csv", "text/csv",
                CSV.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.start(file, "admin"))
                .isInstanceOfSatisfying(ErrorResponseException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getBody().getDetail()).contains("server is busy");
                });

        PatientImport patientImport = saved.get();
        assertThat(patientImport.getStatus()).isEqualTo(ImportStatus.FAILED);
        assertThat(patientImport.getFailureMessage()).contains("could not be started");
        assertThat(patientImport.getFinishedAt()).isEqualTo(NOW);
        verify(audit).recordFinished(patientImport);
        assertStorageEmpty();
    }

    private void assertStorageEmpty() throws IOException {
        try (var files = Files.list(storageDir)) {
            assertThat(files).as("uploaded file deleted").isEmpty();
        }
    }
}
