-- V7: CSV import history and per-row errors.
-- Spring Batch's own tables (V6) describe *executions*; these tables describe *imports* in the
-- terms the UI needs (file, who, counts), and survive even if Batch metadata is cleaned up.
CREATE TABLE patient_import (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name        VARCHAR(255) NOT NULL,
    -- Header line of the uploaded file, reused as-is in the error report so it can be re-imported.
    source_header    TEXT         NOT NULL,
    status           VARCHAR(10)  NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    imported_by      VARCHAR(50)  NOT NULL,
    job_execution_id BIGINT,
    -- Data lines counted at upload; only used to draw the progress bar while the job runs.
    expected_rows    INTEGER      NOT NULL DEFAULT 0,
    valid_rows       INTEGER      NOT NULL DEFAULT 0,
    invalid_rows     INTEGER      NOT NULL DEFAULT 0,
    failure_message  VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ
);

CREATE INDEX idx_patient_import_created ON patient_import (created_at DESC, id DESC);
CREATE INDEX idx_patient_import_status ON patient_import (status);

CREATE TABLE patient_import_error (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_id   BIGINT        NOT NULL REFERENCES patient_import (id) ON DELETE CASCADE,
    -- Line number in the uploaded file (the header is line 1), i.e. the row a spreadsheet shows.
    row_number  INTEGER       NOT NULL,
    reason      VARCHAR(1000) NOT NULL,
    raw_line    TEXT          NOT NULL
);

CREATE INDEX idx_patient_import_error_row ON patient_import_error (import_id, row_number);
