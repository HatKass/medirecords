package com.medirecords.importjob;

import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportRowErrorRepository extends JpaRepository<ImportRowError, Long> {

    Page<ImportRowError> findByImportIdOrderByRowNumberAscIdAsc(long importId, Pageable pageable);

    /** Streams the whole report without loading it into memory. Call inside a read-only transaction. */
    Stream<ImportRowError> streamByImportIdOrderByRowNumberAscIdAsc(long importId);
}
