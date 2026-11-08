-- V8: audit log.
-- One row per business change: a patient created, updated or deleted through the API, or a whole CSV
-- import (one row with its counts, not one per imported patient).
CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Username at the time of the change. Deliberately not a foreign key: the trail must outlive users.
    actor       VARCHAR(50)  NOT NULL,
    action      VARCHAR(10)  NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'IMPORT')),
    entity_type VARCHAR(30)  NOT NULL,
    -- Not a foreign key either: entries for deleted patients must stay.
    entity_id   BIGINT       NOT NULL,
    summary     VARCHAR(500) NOT NULL,
    -- [{"field": "city", "oldValue": "Leeds", "newValue": "York"}, ...]; empty for imports.
    changes     JSONB        NOT NULL DEFAULT '[]',
    -- Extra facts about the action, e.g. an import's file name and row counts.
    details     JSONB        NOT NULL DEFAULT '{}'
);

-- Default order (newest first) and the filters of the audit log page.
CREATE INDEX idx_audit_log_occurred ON audit_log (occurred_at DESC, id DESC);
CREATE INDEX idx_audit_log_actor ON audit_log (actor, occurred_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log (action, occurred_at DESC);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);

-- An audit trail is append-only: refuse UPDATE and DELETE even from code with full table access.
CREATE FUNCTION audit_log_reject_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only (% is not allowed)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_reject_change();
