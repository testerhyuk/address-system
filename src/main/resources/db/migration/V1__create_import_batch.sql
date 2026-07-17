DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_extension
        WHERE extname = 'postgis'
    ) THEN
        RAISE EXCEPTION 'PostGIS extension is required before running database migrations';
    END IF;
END
$$;

CREATE TABLE address.address_import_batch (
    batch_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type VARCHAR(32) NOT NULL,
    source_file_name VARCHAR(255) NOT NULL,
    source_file_sha256 CHAR(64) NOT NULL,
    source_reference_date DATE,
    file_size_bytes BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REGISTERED',
    total_row_count BIGINT,
    accepted_row_count BIGINT,
    rejected_row_count BIGINT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_reason_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_address_import_batch_source_hash
        UNIQUE (source_type, source_file_sha256),
    CONSTRAINT ck_address_import_batch_source_type
        CHECK (source_type IN ('ROAD', 'JIBUN', 'COMMERCIAL')),
    CONSTRAINT ck_address_import_batch_file_name
        CHECK (btrim(source_file_name) <> ''),
    CONSTRAINT ck_address_import_batch_sha256
        CHECK (source_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_address_import_batch_file_size
        CHECK (file_size_bytes >= 0),
    CONSTRAINT ck_address_import_batch_status
        CHECK (status IN ('REGISTERED', 'LOADING', 'VALIDATING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_address_import_batch_total_count
        CHECK (total_row_count IS NULL OR total_row_count >= 0),
    CONSTRAINT ck_address_import_batch_accepted_count
        CHECK (accepted_row_count IS NULL OR accepted_row_count >= 0),
    CONSTRAINT ck_address_import_batch_rejected_count
        CHECK (rejected_row_count IS NULL OR rejected_row_count >= 0),
    CONSTRAINT ck_address_import_batch_processed_count
        CHECK (
            total_row_count IS NULL
            OR COALESCE(accepted_row_count, 0) + COALESCE(rejected_row_count, 0) <= total_row_count
        ),
    CONSTRAINT ck_address_import_batch_completed_at
        CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at),
    CONSTRAINT ck_address_import_batch_failure_reason
        CHECK (status <> 'FAILED' OR failure_reason_code IS NOT NULL)
);

CREATE INDEX idx_address_import_batch_status_created_at
    ON address.address_import_batch (status, created_at);

CREATE INDEX idx_address_import_batch_source_created_at
    ON address.address_import_batch (source_type, created_at DESC);
