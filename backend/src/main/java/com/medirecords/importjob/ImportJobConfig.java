package com.medirecords.importjob;

import com.medirecords.patient.PatientBulkRepository;
import com.medirecords.patient.PatientMapper;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.batch.BatchTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The import pipeline: one chunk-oriented step, {@code reader → processor → writer}.
 *
 * <ul>
 *   <li><b>Reader</b>: streams the file line by line (constant memory for any file size); columns are
 *       matched by header name.</li>
 *   <li><b>Processor</b>: trims, normalizes and validates each row with the API's own rules; returns a
 *       valid or an invalid row instead of throwing.</li>
 *   <li><b>Writer</b>: per chunk, one duplicate-email query, one batch insert, one batch of error rows.</li>
 * </ul>
 *
 * Only lines that cannot be parsed at all, and rows the database refuses, go through Spring Batch's
 * skip mechanism. The job runs on its own thread pool, so the upload request returns immediately.
 */
@Configuration
class ImportJobConfig {

    static final String JOB_NAME = "patientImportJob";
    static final String PARAM_IMPORT_ID = "importId";
    static final String PARAM_FILE = "file";

    @Bean
    Job patientImportJob(JobRepository jobRepository, Step patientImportStep, ImportJobListener listener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(listener)
                .start(patientImportStep)
                .build();
    }

    @Bean
    Step patientImportStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            ImportProperties properties, FlatFileItemReader<CsvRow> patientCsvReader,
            ImportRowProcessor importRowProcessor, ImportChunkWriter importChunkWriter,
            ImportSkipListener importSkipListener) {
        SkipPolicy skipPolicy = (failure, skipCount) ->
                failure instanceof FlatFileParseException || failure instanceof DataIntegrityViolationException;
        return new StepBuilder("patientImportStep", jobRepository)
                .<CsvRow, ImportRow>chunk(properties.chunkSize(), transactionManager)
                .reader(patientCsvReader)
                .processor(importRowProcessor)
                .writer(importChunkWriter)
                .faultTolerant()
                .skipPolicy(skipPolicy)
                // If a chunk is retried row by row after a write failure, reuse the processed rows instead
                // of processing them again (the processor remembers emails and would flag them as duplicates).
                .processorNonTransactional()
                .listener(importSkipListener)
                .build();
    }

    @Bean
    @StepScope
    FlatFileItemReader<CsvRow> patientCsvReader(@Value("#{jobParameters['" + PARAM_FILE + "']}") String file) {
        CsvRowLineMapper lineMapper = new CsvRowLineMapper();
        FlatFileItemReader<CsvRow> reader = new FlatFileItemReaderBuilder<CsvRow>()
                .name("patientCsvReader")
                .resource(new FileSystemResource(file))
                .encoding(StandardCharsets.UTF_8.name())
                .linesToSkip(1)
                .skippedLinesCallback(lineMapper)
                .lineMapper(lineMapper)
                .saveState(false) // imports are not restarted; a failed one is simply uploaded again
                .build();
        reader.setComments(new String[0]); // "#" is ordinary data here, not a comment marker
        return reader;
    }

    @Bean
    @StepScope
    ImportRowProcessor importRowProcessor(Validator validator, PatientMapper mapper) {
        return new ImportRowProcessor(validator, mapper);
    }

    @Bean
    @StepScope
    ImportChunkWriter importChunkWriter(@Value("#{jobParameters['" + PARAM_IMPORT_ID + "']}") Long importId,
            PatientBulkRepository patients, ImportProgressRecorder progress) {
        return new ImportChunkWriter(importId, patients, progress);
    }

    @Bean
    @StepScope
    ImportSkipListener importSkipListener(@Value("#{jobParameters['" + PARAM_IMPORT_ID + "']}") Long importId,
            ImportProgressRecorder progress) {
        return new ImportSkipListener(importId, progress);
    }

    /**
     * Makes Spring Boot's job launcher asynchronous. A small, bounded pool: imports are heavy on the
     * database, so a burst of uploads queues up instead of competing for connections.
     */
    @Bean
    @BatchTaskExecutor
    TaskExecutor importTaskExecutor(ImportProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("import-");
        executor.setCorePoolSize(properties.maxConcurrentJobs());
        executor.setMaxPoolSize(properties.maxConcurrentJobs());
        executor.setQueueCapacity(50);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
