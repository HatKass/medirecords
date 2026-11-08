package com.medirecords.importjob;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param storageDir        where uploaded files wait for their job (deleted afterwards)
 * @param chunkSize         rows per chunk, i.e. per database transaction
 * @param maxConcurrentJobs imports that may run at the same time; later ones queue
 */
@ConfigurationProperties("app.patient-import")
public record ImportProperties(Path storageDir, int chunkSize, int maxConcurrentJobs) {

    public ImportProperties {
        if (chunkSize < 1 || maxConcurrentJobs < 1) {
            throw new IllegalArgumentException("chunk-size and max-concurrent-jobs must be at least 1");
        }
    }
}
