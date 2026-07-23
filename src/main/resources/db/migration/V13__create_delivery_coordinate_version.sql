CREATE TABLE address.delivery_coordinate_version (
    coordinate_id UUID PRIMARY KEY,
    delivery_target_id UUID NOT NULL,
    version_no BIGINT NOT NULL,
    location GEOGRAPHY(POINT, 4326) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    quality_score NUMERIC(5, 4),
    sample_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,

    CONSTRAINT fk_delivery_coordinate_version_target
        FOREIGN KEY (delivery_target_id)
        REFERENCES address.delivery_target (delivery_target_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_delivery_coordinate_version
        UNIQUE (delivery_target_id, version_no),
    CONSTRAINT ck_delivery_coordinate_version_number CHECK (version_no > 0),
    CONSTRAINT ck_delivery_coordinate_version_source
        CHECK (source_type IN ('ANALYSIS', 'MANUAL', 'RESTORED')),
    CONSTRAINT ck_delivery_coordinate_version_status
        CHECK (status IN ('CANDIDATE', 'ACTIVE', 'SUPERSEDED', 'EXCLUDED')),
    CONSTRAINT ck_delivery_coordinate_version_quality
        CHECK (quality_score IS NULL OR quality_score BETWEEN 0 AND 1),
    CONSTRAINT ck_delivery_coordinate_version_samples CHECK (sample_count >= 0),
    CONSTRAINT ck_delivery_coordinate_version_active_time
        CHECK ((status = 'ACTIVE') = (activated_at IS NOT NULL AND retired_at IS NULL))
);

CREATE UNIQUE INDEX uq_delivery_coordinate_version_active
    ON address.delivery_coordinate_version (delivery_target_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_delivery_coordinate_version_location
    ON address.delivery_coordinate_version USING GIST (location);
