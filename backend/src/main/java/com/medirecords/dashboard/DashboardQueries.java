package com.medirecords.dashboard;

import com.medirecords.audit.AuditAction;
import com.medirecords.dashboard.DashboardDtos.ImportSummary;
import com.medirecords.dashboard.DashboardDtos.RecentActivity;
import com.medirecords.dashboard.DashboardDtos.RecentImport;
import com.medirecords.importjob.ImportStatus;
import com.medirecords.patient.BloodType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Read-only aggregate queries behind the dashboard, kept in one place rather than spread over the
 * feature repositories: they read across features (patients, imports, audit) and exist only for this
 * page. Each method is a single SQL statement that returns numbers or a handful of narrow rows; no
 * entity is loaded into the persistence context.
 */
@Repository
class DashboardQueries {

    /** Raw per-blood-type counts; {@code bloodType} is null for "not recorded". */
    record BloodTypeTotals(BloodType bloodType, long count, long addedSince) {}

    private final EntityManager entityManager;

    DashboardQueries(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** One GROUP BY over patients gives both the chart and, summed, the totals. */
    List<BloodTypeTotals> patientsByBloodType(Instant since) {
        return entityManager.createQuery("""
                        SELECT p.bloodType, count(p), sum(CASE WHEN p.createdAt >= :since THEN 1 ELSE 0 END)
                        FROM Patient p
                        GROUP BY p.bloodType""", Object[].class)
                .setParameter("since", since)
                .getResultList().stream()
                .map(row -> new BloodTypeTotals((BloodType) row[0], number(row[1]), number(row[2])))
                .toList();
    }

    /** One row of conditional aggregates over the imports created since {@code since}. */
    ImportSummary importSummary(Instant since) {
        Object[] row = entityManager.createQuery("""
                        SELECT count(i),
                               sum(CASE WHEN i.status = :failed THEN 1 ELSE 0 END),
                               sum(CASE WHEN i.status IN :active THEN 1 ELSE 0 END),
                               sum(i.validRows),
                               sum(i.invalidRows)
                        FROM PatientImport i
                        WHERE i.createdAt >= :since""", Object[].class)
                .setParameter("failed", ImportStatus.FAILED)
                .setParameter("active", List.of(ImportStatus.QUEUED, ImportStatus.RUNNING))
                .setParameter("since", since)
                .getSingleResult();
        // SUM over no rows is NULL, hence number() mapping null to 0.
        return new ImportSummary(number(row[0]), number(row[1]), number(row[2]), number(row[3]), number(row[4]));
    }

    /** Newest imports (uses idx_patient_import_created). */
    List<RecentImport> recentImports(int limit) {
        return entityManager.createQuery("""
                        SELECT i.id, i.fileName, i.status, i.importedBy, i.validRows, i.invalidRows, i.createdAt
                        FROM PatientImport i
                        ORDER BY i.createdAt DESC, i.id DESC""", Object[].class)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(row -> new RecentImport((Long) row[0], (String) row[1], (ImportStatus) row[2], (String) row[3],
                        (Integer) row[4], (Integer) row[5], (Instant) row[6]))
                .toList();
    }

    /**
     * Newest audit entries of the given actions (uses idx_audit_log_occurred). Selects only the summary
     * columns, never the JSON old/new values.
     */
    List<RecentActivity> recentActivity(List<AuditAction> actions, int limit) {
        return entityManager.createQuery("""
                        SELECT a.id, a.occurredAt, a.actor, a.action, a.entityType, a.entityId, a.summary
                        FROM AuditEntry a
                        WHERE a.action IN :actions
                        ORDER BY a.occurredAt DESC, a.id DESC""", Object[].class)
                .setParameter("actions", actions)
                .setMaxResults(limit)
                .getResultList().stream()
                .map(row -> new RecentActivity((Long) row[0], (Instant) row[1], (String) row[2], (AuditAction) row[3],
                        (String) row[4], (Long) row[5], (String) row[6]))
                .toList();
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
