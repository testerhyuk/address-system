CREATE SCHEMA IF NOT EXISTS coordinate_raw;

CREATE TABLE coordinate_raw.delivery_coordinate_sample (
    sample_id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    delivery_target_id UUID NOT NULL,
    location GEOGRAPHY(POINT, 4326) NOT NULL,
    gps_accuracy_meters NUMERIC(8, 2) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    processing_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    failure_code VARCHAR(64),
    retry_count INTEGER NOT NULL DEFAULT 0,
    processed_at TIMESTAMPTZ,

    CONSTRAINT fk_delivery_coordinate_target
        FOREIGN KEY (delivery_target_id)
        REFERENCES address.delivery_target (delivery_target_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_delivery_coordinate_accuracy
        CHECK (gps_accuracy_meters > 0),
    CONSTRAINT ck_delivery_coordinate_retention
        CHECK (expires_at > received_at),
    CONSTRAINT ck_delivery_coordinate_status
        CHECK (processing_status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_delivery_coordinate_failure
        CHECK ((processing_status = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT ck_delivery_coordinate_retry
        CHECK (retry_count >= 0)
);

CREATE INDEX idx_delivery_coordinate_pending
    ON coordinate_raw.delivery_coordinate_sample (received_at)
    WHERE processing_status IN ('PENDING', 'FAILED');

CREATE INDEX idx_delivery_coordinate_target_time
    ON coordinate_raw.delivery_coordinate_sample (delivery_target_id, completed_at);

CREATE INDEX idx_delivery_coordinate_expiration
    ON coordinate_raw.delivery_coordinate_sample (expires_at);

COMMENT ON TABLE coordinate_raw.delivery_coordinate_sample IS
    '회원·주문·기사 데이터와 분리하여 제한 기간만 보관하는 배달 완료 원본 좌표';
