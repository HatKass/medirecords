-- V4: patients.
-- Enumerations are stored as their API names and guarded by CHECK constraints, which keeps the
-- table readable in SQL tools and lets the CSV import fail fast on bad values.
CREATE TABLE patient (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    date_of_birth DATE         NOT NULL,
    gender        VARCHAR(10)  NOT NULL CHECK (gender IN ('FEMALE', 'MALE', 'OTHER')),
    email         VARCHAR(254) NOT NULL,
    phone         VARCHAR(20),
    city          VARCHAR(100) NOT NULL,
    blood_type    VARCHAR(12)  CHECK (blood_type IN ('A_POSITIVE', 'A_NEGATIVE', 'B_POSITIVE', 'B_NEGATIVE',
                                                     'AB_POSITIVE', 'AB_NEGATIVE', 'O_POSITIVE', 'O_NEGATIVE')),
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Emails are stored lower-cased by the application; the expression index also makes the
-- uniqueness check case-insensitive for rows written by other means.
CREATE UNIQUE INDEX uk_patient_email ON patient (lower(email));

-- "Name contains" search over "first last" (pg_trgm from V1 makes ILIKE '%term%' indexable).
CREATE INDEX idx_patient_full_name_trgm ON patient
    USING gin (lower(first_name || ' ' || last_name) gin_trgm_ops);

CREATE INDEX idx_patient_city ON patient (lower(city));
CREATE INDEX idx_patient_blood_type ON patient (blood_type);
-- Default list order.
CREATE INDEX idx_patient_name_order ON patient (last_name, first_name, id);
