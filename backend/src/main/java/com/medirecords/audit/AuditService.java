package com.medirecords.audit;

import com.medirecords.audit.AuditDtos.AuditEntryResponse;
import com.medirecords.audit.AuditDtos.AuditFilter;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads the audit log.
 *
 * <p>Every {@code record...} method requires the caller's transaction ({@link Propagation#MANDATORY}):
 * the audit row commits together with the change it describes, or neither does. There is no window in
 * which a change exists without its audit entry, or an entry for a change that was rolled back.
 */
@Service
public class AuditService {

    /** Actor recorded when no user is signed in (should not happen for API writes, which require ADMIN). */
    static final String SYSTEM_ACTOR = "system";
    static final String AUTH_ENTITY = "Session";
    private static final int MAX_ACTOR = 50;

    private final AuditEntryRepository entries;
    private final Clock clock;

    public AuditService(AuditEntryRepository entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T extends Record> void recordCreate(String entityType, long entityId, String label, T created) {
        record(currentActor(), AuditAction.CREATE, entityType, entityId, "Created " + label,
                FieldChange.between(null, created), Map.of());
    }

    /** Records nothing if no field actually changed (a save without edits is not a change). */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T extends Record> void recordUpdate(String entityType, long entityId, String label, T before, T after) {
        List<FieldChange> changes = FieldChange.between(before, after);
        if (changes.isEmpty()) {
            return;
        }
        String fields = String.join(", ", changes.stream().map(FieldChange::field).toList());
        record(currentActor(), AuditAction.UPDATE, entityType, entityId, "Updated " + label + " (" + fields + ")",
                changes, Map.of());
    }

    /** Keeps the deleted values, so the entry shows what was removed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T extends Record> void recordDelete(String entityType, long entityId, String label, T deleted) {
        record(currentActor(), AuditAction.DELETE, entityType, entityId, "Deleted " + label,
                FieldChange.between(deleted, null), Map.of());
    }

    /**
     * One entry for a whole import. The actor is passed in because imports run on a batch thread,
     * where there is no signed-in user.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordImport(String actor, long importId, String summary, Map<String, Object> details) {
        record(actor, AuditAction.IMPORT, "Import", importId, summary, List.of(), details);
    }

    /**
     * A sign-in, failed sign-in or sign-out: only the username and the time are kept, never a password
     * or token. Runs in its own transaction ({@link Propagation#REQUIRES_NEW}) because there is no
     * business change to join, and a failed login must be recorded although the request then fails.
     *
     * @param username for {@link AuditAction#LOGIN_FAILED}, the username as typed (trimmed, capped at
     *                 the column length); it may not belong to any account
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthentication(AuditAction action, String username) {
        recordAuthentication(action, username, Map.of());
    }

    /**
     * As {@link #recordAuthentication(AuditAction, String)}, with extra facts; used for
     * {@link AuditAction#LOGIN_BLOCKED} (client address and how long it is blocked). Never a password.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthentication(AuditAction action, String username, Map<String, Object> details) {
        if (!action.isAuthentication()) {
            throw new IllegalArgumentException(action + " is not an authentication event");
        }
        String actor = username == null || username.isBlank() ? "(blank)" : username.strip();
        if (actor.length() > MAX_ACTOR) {
            actor = actor.substring(0, MAX_ACTOR);
        }
        String summary = switch (action) {
            case LOGIN_SUCCESS -> "Signed in";
            case LOGIN_FAILED -> "Failed sign-in attempt";
            case LOGIN_BLOCKED -> "Sign-in blocked after too many failed attempts";
            case LOGOUT -> "Signed out";
            default -> throw new IllegalStateException();
        };
        record(actor, action, AUTH_ENTITY, null, summary, List.of(), details);
    }

    @Transactional(readOnly = true)
    public Page<AuditEntryResponse> search(AuditFilter filter, Pageable pageable) {
        return entries.findAll(matching(filter), pageable).map(AuditEntryResponse::from);
    }

    @Transactional(readOnly = true)
    public List<String> actors() {
        return entries.findDistinctActors();
    }

    private void record(String actor, AuditAction action, String entityType, Long entityId, String summary,
            List<FieldChange> changes, Map<String, Object> details) {
        entries.save(new AuditEntry(clock.instant(), actor, action, entityType, entityId, summary, changes, details));
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() ? SYSTEM_ACTOR : authentication.getName();
    }

    private static Specification<AuditEntry> matching(AuditFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.actor() != null) {
                predicates.add(cb.equal(root.get("actor"), filter.actor()));
            }
            if (filter.action() != null) {
                predicates.add(cb.equal(root.get("action"), filter.action()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), filter.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
