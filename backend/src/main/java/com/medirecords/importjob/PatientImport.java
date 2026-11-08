package com.medirecords.importjob;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One CSV upload and its outcome. The row counters are incremented by the batch job in the same
 * transaction as the rows themselves (see {@link ImportProgressRecorder}), so they are always exact.
 */
@Entity
@Table(name = "patient_import")
public class PatientImport {

    private static final int MAX_FAILURE_MESSAGE = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "source_header", nullable = false)
    private String sourceHeader;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ImportStatus status;

    @Column(name = "imported_by", nullable = false, length = 50)
    private String importedBy;

    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Column(name = "expected_rows", nullable = false)
    private int expectedRows;

    // Only ever changed by the atomic increment in the repository, never by saving this entity.
    @Column(name = "valid_rows", nullable = false, insertable = false, updatable = false)
    private int validRows;

    @Column(name = "invalid_rows", nullable = false, insertable = false, updatable = false)
    private int invalidRows;

    @Column(name = "failure_message", length = MAX_FAILURE_MESSAGE)
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected PatientImport() {
        // for JPA
    }

    PatientImport(String fileName, String sourceHeader, int expectedRows, String importedBy, Instant createdAt) {
        this.fileName = fileName;
        this.sourceHeader = sourceHeader;
        this.expectedRows = expectedRows;
        this.importedBy = importedBy;
        this.createdAt = createdAt;
        this.status = ImportStatus.QUEUED;
    }

    void markRunning(long jobExecutionId, Instant startedAt) {
        this.jobExecutionId = jobExecutionId;
        this.startedAt = startedAt;
        this.status = ImportStatus.RUNNING;
    }

    void markCompleted(Instant finishedAt) {
        this.finishedAt = finishedAt;
        this.status = ImportStatus.COMPLETED;
    }

    void markFailed(String message, Instant finishedAt) {
        this.failureMessage = message.length() > MAX_FAILURE_MESSAGE ? message.substring(0, MAX_FAILURE_MESSAGE) : message;
        this.finishedAt = finishedAt;
        this.status = ImportStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getSourceHeader() {
        return sourceHeader;
    }

    public ImportStatus getStatus() {
        return status;
    }

    public String getImportedBy() {
        return importedBy;
    }

    public Long getJobExecutionId() {
        return jobExecutionId;
    }

    public int getExpectedRows() {
        return expectedRows;
    }

    public int getValidRows() {
        return validRows;
    }

    public int getInvalidRows() {
        return invalidRows;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
