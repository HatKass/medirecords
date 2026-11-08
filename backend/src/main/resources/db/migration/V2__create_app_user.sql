-- V2: application users and the two demo accounts.
-- Roles are a fixed, small set, so they live in a constrained column rather than a join table.
CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'VIEWER')),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

-- Demo users (password for both: demo123, BCrypt cost 10).
INSERT INTO app_user (username, password_hash, full_name, role) VALUES
    ('admin',  '$2a$10$i0D1hKOiv3HDgDVwHxp7ZefXDCrNEOcSJ0Z2rUU7mXLmL9f2Pld.a', 'Dr. Alex Morgan', 'ADMIN'),
    ('viewer', '$2a$10$i0D1hKOiv3HDgDVwHxp7ZefXDCrNEOcSJ0Z2rUU7mXLmL9f2Pld.a', 'Sam Taylor',      'VIEWER');
