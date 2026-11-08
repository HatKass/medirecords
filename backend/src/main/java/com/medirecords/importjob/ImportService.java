package com.medirecords.importjob;

import com.medirecords.common.web.BadRequestException;
import com.medirecords.common.web.ResourceNotFoundException;
import com.medirecords.importjob.ImportDtos.ImportErrorResponse;
import com.medirecords.importjob.ImportDtos.ImportResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final int MAX_FILE_NAME = 255;
    private static final String NOT_STARTED = "The import could not be started because the server is busy. "
            + "Try again in a minute.";

    private final PatientImportRepository imports;
    private final ImportRowErrorRepository errors;
    private final ImportFileStorage storage;
    private final JobLauncher jobLauncher;
    private final Job patientImportJob;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ImportService(PatientImportRepository imports, ImportRowErrorRepository errors, ImportFileStorage storage,
            JobLauncher jobLauncher, Job patientImportJob, TransactionTemplate transaction, Clock clock) {
        this.imports = imports;
        this.errors = errors;
        this.storage = storage;
        this.jobLauncher = jobLauncher;
        this.patientImportJob = patientImportJob;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * Checks the file up front (so obvious mistakes get an immediate 400 instead of a failed job),
     * records the import and starts the job asynchronously. Returns while the job is still queued.
     */
    public ImportResponse start(MultipartFile file, String username) {
        String fileName = fileName(file);
        Path stored;
        try (InputStream content = file.getInputStream()) {
            stored = storage.store(content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        PatientImport patientImport;
        try {
            FileSummary summary = summarize(stored);
            // Committed before launching: the job thread reads this row as soon as it starts.
            patientImport = transaction.execute(status -> imports.save(new PatientImport(
                    fileName, summary.header().line(), summary.dataRows(), username, clock.instant())));
        } catch (RuntimeException exception) {
            storage.delete(stored);
            throw exception;
        }

        launch(patientImport, stored);
        log.info("Import {} of {} ({} rows) queued by {}", patientImport.getId(), fileName,
                patientImport.getExpectedRows(), username);
        return get(patientImport.getId());
    }

    @Transactional(readOnly = true)
    public Page<ImportResponse> list(Pageable pageable) {
        return imports.findAll(pageable).map(ImportResponse::from);
    }

    @Transactional(readOnly = true)
    public ImportResponse get(long id) {
        return ImportResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public Page<ImportErrorResponse> errors(long id, Pageable pageable) {
        find(id);
        return errors.findByImportIdOrderByRowNumberAscIdAsc(id, pageable).map(ImportErrorResponse::from);
    }

    /**
     * Writes the rejected rows as CSV: {@code row,error,<the original columns>}. Each line holds the
     * original text untouched, so the file can be fixed in a spreadsheet and uploaded again (the import
     * ignores the extra "row" and "error" columns). Starts with a BOM so Excel detects UTF-8.
     */
    @Transactional(readOnly = true)
    public void writeErrorReport(long id, PrintWriter out) {
        PatientImport patientImport = find(id);
        out.print('﻿');
        out.print("row,error," + patientImport.getSourceHeader() + "\r\n");
        try (Stream<ImportRowError> rows = errors.streamByImportIdOrderByRowNumberAscIdAsc(id)) {
            rows.forEach(error -> out.print(error.getRowNumber() + "," + quote(error.getReason()) + ","
                    + error.getRawLine() + "\r\n"));
        }
        out.flush();
    }

    /** Suggested download name: "patients.csv" gives "patients-errors.csv". */
    public static String errorReportFileName(ImportResponse patientImport) {
        String base = StringUtils.stripFilenameExtension(patientImport.fileName());
        return (base.isBlank() ? "import-" + patientImport.id() : base) + "-errors.csv";
    }

    /**
     * Jobs run in this process, so an import still QUEUED or RUNNING at start-up was cut off by a
     * restart and will never finish. Marking it failed keeps the history honest. (With several backend
     * instances, this would need a per-instance owner column.)
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failInterruptedImports() {
        for (PatientImport interrupted : imports.findByStatusIn(List.of(ImportStatus.QUEUED, ImportStatus.RUNNING))) {
            interrupted.markFailed("The import was interrupted by a server restart. Rows imported before "
                    + "this point were kept; upload the file again to import the rest.", clock.instant());
            log.warn("Marked interrupted import {} as failed", interrupted.getId());
        }
    }

    private void launch(PatientImport patientImport, Path file) {
        JobParameters parameters = new JobParametersBuilder()
                .addLong(ImportJobConfig.PARAM_IMPORT_ID, patientImport.getId())
                .addString(ImportJobConfig.PARAM_FILE, file.toString(), false)
                .toJobParameters();
        JobExecution execution;
        try {
            execution = jobLauncher.run(patientImportJob, parameters);
        } catch (Exception exception) {
            log.error("Could not launch import {}", patientImport.getId(), exception);
            execution = null;
        }
        // The async launcher reports a full worker queue by returning an execution that FAILED without starting.
        if (execution == null || execution.getStatus() == BatchStatus.FAILED && execution.getStartTime() == null) {
            transaction.executeWithoutResult(status -> imports.findById(patientImport.getId())
                    .ifPresent(queued -> queued.markFailed(NOT_STARTED, clock.instant())));
            storage.delete(file);
            throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, NOT_STARTED), null);
        }
    }

    private record FileSummary(CsvHeader header, int dataRows) {}

    /** Reads the header and counts the non-blank data lines (for the progress bar). */
    private static FileSummary summarize(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new BadRequestException("The file is empty. The first line must be a header row.");
            }
            if (headerLine.indexOf('\0') >= 0) {
                throw new BadRequestException("This does not look like a text CSV file.");
            }
            CsvHeader header = CsvHeader.parse(headerLine);
            if (!header.isValid()) {
                throw new BadRequestException("The header row is missing these columns: "
                        + String.join(", ", header.missingColumns()) + ". Expected columns: "
                        + String.join(", ", CsvHeader.REQUIRED) + " (required) and "
                        + String.join(", ", CsvHeader.OPTIONAL) + " (optional), in any order.");
            }
            int dataRows = (int) reader.lines().filter(line -> !line.isBlank()).count();
            if (dataRows == 0) {
                throw new BadRequestException("The file has a header row but no data rows.");
            }
            return new FileSummary(header, dataRows);
        } catch (CharacterCodingException exception) {
            throw notUtf8();
        } catch (UncheckedIOException exception) { // from reader.lines()
            if (exception.getCause() instanceof CharacterCodingException) {
                throw notUtf8();
            }
            throw exception;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static BadRequestException notUtf8() {
        return new BadRequestException("The file is not UTF-8 encoded. Save it as \"CSV UTF-8\" and try again.");
    }

    private static String fileName(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("The file is empty.");
        }
        String name = StringUtils.getFilename(StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/')));
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new BadRequestException("Only .csv files can be imported.");
        }
        return name.length() > MAX_FILE_NAME ? name.substring(name.length() - MAX_FILE_NAME) : name;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private PatientImport find(long id) {
        return imports.findById(id).orElseThrow(() -> new ResourceNotFoundException("Import", id));
    }
}
