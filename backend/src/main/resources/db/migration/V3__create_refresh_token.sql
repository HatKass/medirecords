-- V3: refresh tokens for cookie-based sessions.
-- Only a SHA-256 hash of each token is stored, so a database leak does not leak usable tokens.
-- Every login starts a "family"; each refresh revokes the presented token and issues a new one
-- in the same family. Presenting an already-revoked token (a replay) revokes the whole family.
CREATE TABLE refresh_token (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    family_id  UUID         NOT NULL,
    token_hash VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_expires_at ON refresh_token (expires_at);
