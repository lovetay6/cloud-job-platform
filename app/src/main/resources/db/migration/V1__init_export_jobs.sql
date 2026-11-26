CREATE TABLE export_jobs (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(128) NOT NULL UNIQUE,
    report_type      VARCHAR(64)  NOT NULL,
    row_count        INTEGER      NOT NULL,
    simulate_failure BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(24)  NOT NULL,
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    worker_id        VARCHAR(128),
    lease_until      TIMESTAMP WITH TIME ZONE,
    s3_key           VARCHAR(512),
    file_size_bytes  BIGINT,
    error_message    VARCHAR(1000),
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE,
    completed_at     TIMESTAMP WITH TIME ZONE,
    updated_at       TIMESTAMP WITH TIME ZONE  NOT NULL
);

CREATE INDEX idx_export_jobs_created_at ON export_jobs (created_at DESC);
CREATE INDEX idx_export_jobs_status_lease ON export_jobs (status, lease_until);
