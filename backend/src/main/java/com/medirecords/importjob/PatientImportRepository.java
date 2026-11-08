package com.medirecords.importjob;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientImportRepository extends JpaRepository<PatientImport, Long> {

    List<PatientImport> findByStatusIn(Collection<ImportStatus> statuses);

    /** Atomic increment, so the counters never depend on a stale copy of the row in memory. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PatientImport i SET i.validRows = i.validRows + :valid, i.invalidRows = i.invalidRows + :invalid "
            + "WHERE i.id = :id")
    void incrementCounts(@Param("id") long id, @Param("valid") int valid, @Param("invalid") int invalid);
}
