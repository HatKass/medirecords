package com.medirecords.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One audit log row. Immutable: written once, never updated (the database enforces this too). */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditEntry {

    static final int MAX_SUMMARY = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 50)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditAction action;

    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    /** Null for authentication events, which are not about an entity. */
    @Column(name = "entity_id")
    private Long entityId;

    @Column(nullable = false, length = MAX_SUMMARY)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<FieldChange> changes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> details;

    protected AuditEntry() {
        // for JPA
    }

    AuditEntry(Instant occurredAt, String actor, AuditAction action, String entityType, Long entityId,
            String summary, List<FieldChange> changes, Map<String, Object> details) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.summary = summary.length() > MAX_SUMMARY ? summary.substring(0, MAX_SUMMARY - 1) + "…" : summary;
        this.changes = List.copyOf(changes);
        this.details = new LinkedHashMap<>(details); // keeps the caller's key order in the JSON
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public String getSummary() {
        return summary;
    }

    public List<FieldChange> getChanges() {
        return changes;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
