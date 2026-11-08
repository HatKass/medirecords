package com.medirecords.importjob;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps an uploaded file on local disk until its job has read it. Files are named by a random id,
 * never by the client's file name, so an upload cannot choose where it is written.
 */
@Component
class ImportFileStorage {

    private static final Logger log = LoggerFactory.getLogger(ImportFileStorage.class);

    private final Path directory;

    ImportFileStorage(ImportProperties properties) {
        this.directory = properties.storageDir().toAbsolutePath().normalize();
    }

    Path store(InputStream content) {
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(UUID.randomUUID() + ".csv");
            Files.copy(content, target);
            return target;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not store the uploaded file", exception);
        }
    }

    void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Could not delete import file {}", file, exception);
        }
    }
}
