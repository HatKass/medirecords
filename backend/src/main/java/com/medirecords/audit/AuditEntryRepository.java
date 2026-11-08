package com.medirecords.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Insert and read only: deliberately no delete or update methods (see V8's append-only trigger). */
interface AuditEntryRepository extends Repository<AuditEntry, Long>, JpaSpecificationExecutor<AuditEntry> {

    AuditEntry save(AuditEntry entry);

    @Query("SELECT DISTINCT e.actor FROM AuditEntry e ORDER BY e.actor")
    List<String> findDistinctActors();
}
