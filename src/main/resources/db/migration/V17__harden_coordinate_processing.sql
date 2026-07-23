ALTER TABLE coordinate_raw.delivery_coordinate_sample
    ADD CONSTRAINT ck_delivery_coordinate_max_retention
        CHECK (expires_at <= received_at + INTERVAL '30 days');

REVOKE ALL ON SCHEMA coordinate_raw FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA coordinate_raw FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA coordinate_raw FROM PUBLIC;

ALTER DEFAULT PRIVILEGES IN SCHEMA coordinate_raw
    REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA coordinate_raw
    REVOKE ALL ON SEQUENCES FROM PUBLIC;

CREATE TABLE address.coordinate_processing_failure (
    failure_id UUID PRIMARY KEY,
    stage VARCHAR(16) NOT NULL,
    delivery_target_id UUID NOT NULL,
    candidate_id UUID,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL,
    failure_code VARCHAR(100) NOT NULL,
    first_failed_at TIMESTAMPTZ NOT NULL,
    last_failed_at TIMESTAMPTZ NOT NULL,
    next_retry_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,

    CONSTRAINT fk_coordinate_processing_failure_target
        FOREIGN KEY (delivery_target_id)
        REFERENCES address.delivery_target (delivery_target_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_coordinate_processing_failure_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES address.delivery_coordinate_candidate (candidate_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_coordinate_processing_failure
        UNIQUE NULLS NOT DISTINCT (stage, delivery_target_id, candidate_id),
    CONSTRAINT ck_coordinate_processing_failure_stage
        CHECK (stage IN ('ANALYSIS', 'QUALITY')),
    CONSTRAINT ck_coordinate_processing_failure_stage_target
        CHECK (
            (stage = 'ANALYSIS' AND candidate_id IS NULL)
            OR (stage = 'QUALITY' AND candidate_id IS NOT NULL)
        ),
    CONSTRAINT ck_coordinate_processing_failure_status
        CHECK (status IN ('RETRY_SCHEDULED', 'EXHAUSTED', 'RESOLVED')),
    CONSTRAINT ck_coordinate_processing_failure_attempt
        CHECK (attempt_count > 0),
    CONSTRAINT ck_coordinate_processing_failure_schedule
        CHECK (
            (status = 'RETRY_SCHEDULED'
                AND next_retry_at IS NOT NULL AND resolved_at IS NULL)
            OR (status = 'EXHAUSTED'
                AND next_retry_at IS NULL AND resolved_at IS NULL)
            OR (status = 'RESOLVED'
                AND next_retry_at IS NULL AND resolved_at IS NOT NULL)
        )
);

CREATE INDEX idx_coordinate_processing_failure_retry
    ON address.coordinate_processing_failure (next_retry_at)
    WHERE status = 'RETRY_SCHEDULED';

CREATE INDEX idx_coordinate_processing_failure_target
    ON address.coordinate_processing_failure (
        delivery_target_id, stage, status
    );

COMMENT ON TABLE address.coordinate_processing_failure IS
    '원본 좌표나 예외 메시지를 저장하지 않는 좌표 처리 실패 및 재시도 상태';
