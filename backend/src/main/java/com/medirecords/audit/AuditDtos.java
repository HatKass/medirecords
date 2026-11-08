package com.medirecords.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API shapes for the audit log. */
public final class AuditDtos {

    private AuditDtos() {
    }

    public record AuditEntryResponse(
            long id,
            Instant occurredAt,
            String actor,
            AuditAction action,
            String entityType,
            /** Null for authentication events. */
            Long entityId,
            String summary,
            List<FieldChange> changes,
            Map<String, Object> details) {

        static AuditEntryResponse from(AuditEntry entry) {
            return new AuditEntryResponse(entry.getId(), entry.getOccurredAt(), entry.getActor(), entry.getAction(),
                    entry.getEntityType(), entry.getEntityId(), entry.getSummary(), entry.getChanges(),
                    entry.getDetails());
        }
    }

    /** Optional filters; null means "do not filter on this". {@code from} is inclusive, {@code to} exclusive. */
    public record AuditFilter(String actor, AuditAction action, Instant from, Instant to) {}
}
