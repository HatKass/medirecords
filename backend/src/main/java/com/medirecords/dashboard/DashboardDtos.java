package com.medirecords.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.medirecords.audit.AuditAction;
import com.medirecords.importjob.ImportStatus;
import com.medirecords.patient.BloodType;
import java.time.Instant;
import java.util.List;

/** API shapes for the dashboard. Every number is computed by the database, never by loading rows. */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /**
     * @param since          start of the rolling window used by the "last 7 days" figures
     * @param recentActivity latest data changes from the audit log; ADMIN only, so the field is left out
     *                       of the JSON entirely for viewers (not sent as null)
     */
    public record DashboardResponse(
            Instant generatedAt,
            Instant since,
            PatientSummary patients,
            ImportSummary imports,
            List<RecentImport> recentImports,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<RecentActivity> recentActivity) {}

    /**
     * @param byBloodType one entry per blood type in a fixed order (zeros included, so the chart keeps
     *                    its shape), plus a {@code null} blood type for patients whose type is unknown
     */
    public record PatientSummary(long total, long addedSince, List<BloodTypeCount> byBloodType) {}

    /** {@code bloodType} is null for "not recorded". */
    public record BloodTypeCount(BloodType bloodType, long count) {}

    /**
     * Imports started in the window.
     *
     * @param failedImports imports that stopped early (status FAILED)
     * @param inProgress    imports still queued or running
     * @param validRows     rows imported as patients
     * @param invalidRows   rows rejected by validation (the "failed rows" of the error reports)
     */
    public record ImportSummary(long count, long failedImports, long inProgress, long validRows, long invalidRows) {}

    public record RecentImport(long id, String fileName, ImportStatus status, String importedBy, int validRows,
            int invalidRows, Instant createdAt) {}

    /** A slim audit entry: no old/new values, which the dashboard does not show. */
    public record RecentActivity(long id, Instant occurredAt, String actor, AuditAction action, String entityType,
            Long entityId, String summary) {}
}
