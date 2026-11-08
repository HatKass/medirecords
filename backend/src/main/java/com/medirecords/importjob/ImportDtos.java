package com.medirecords.importjob;

import java.time.Instant;

/** API shapes for CSV imports. */
public final class ImportDtos {

    private ImportDtos() {
    }

    /**
     * @param totalRows       rows processed so far (valid + invalid); the file's row count once finished
     * @param expectedRows    data lines counted at upload, used for the progress bar
     * @param progressPercent 0–100; reaches 100 only when the job has finished
     */
    public record ImportResponse(
            long id,
            Long jobExecutionId,
            String fileName,
            ImportStatus status,
            String importedBy,
            int expectedRows,
            int totalRows,
            int validRows,
            int invalidRows,
            int progressPercent,
            String failureMessage,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) {

        static ImportResponse from(PatientImport patientImport) {
            int processed = patientImport.getValidRows() + patientImport.getInvalidRows();
            int expected = patientImport.getExpectedRows();
            int progress = patientImport.getStatus().isFinished() ? 100
                    : expected == 0 ? 0 : Math.min(99, (int) (processed * 100L / expected));
            return new ImportResponse(patientImport.getId(), patientImport.getJobExecutionId(),
                    patientImport.getFileName(), patientImport.getStatus(), patientImport.getImportedBy(), expected,
                    processed, patientImport.getValidRows(), patientImport.getInvalidRows(), progress,
                    patientImport.getFailureMessage(), patientImport.getCreatedAt(), patientImport.getStartedAt(),
                    patientImport.getFinishedAt());
        }
    }

    public record ImportErrorResponse(int rowNumber, String reason, String rawLine) {

        static ImportErrorResponse from(ImportRowError error) {
            return new ImportErrorResponse(error.getRowNumber(), error.getReason(), error.getRawLine());
        }
    }
}
