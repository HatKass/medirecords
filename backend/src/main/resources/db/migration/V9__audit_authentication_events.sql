-- V9: authentication events in the audit log (LOGIN_SUCCESS, LOGIN_FAILED, LOGOUT).
-- They record only who and when: actor is the username (for a failed login, the one that was typed),
-- occurred_at the time. No entity is involved, so entity_id becomes optional.
ALTER TABLE audit_log ALTER COLUMN action TYPE VARCHAR(20);
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'IMPORT', 'LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGOUT'));

ALTER TABLE audit_log ALTER COLUMN entity_id DROP NOT NULL;
