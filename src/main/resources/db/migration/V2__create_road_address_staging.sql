CREATE TABLE address.address_road_staging (
    batch_id UUID NOT NULL,
    source_row_number BIGINT NOT NULL,
    mgmt_num TEXT,
    sido TEXT,
    sigungu TEXT,
    b_dong_name TEXT,
    road_name TEXT,
    build_main TEXT,
    build_sub TEXT,
    zip_code TEXT,
    build_nm_official TEXT,
    build_nm_sgg TEXT,
    processing_status VARCHAR(16) NOT NULL DEFAULT 'LOADED',
    processed_at TIMESTAMPTZ,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_address_road_staging
        PRIMARY KEY (batch_id, source_row_number),
    CONSTRAINT fk_address_road_staging_batch
        FOREIGN KEY (batch_id)
        REFERENCES address.address_import_batch (batch_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_address_road_staging_source_row
        CHECK (source_row_number > 1),
    CONSTRAINT ck_address_road_staging_processing_status
        CHECK (processing_status IN ('LOADED', 'VALID', 'REJECTED')),
    CONSTRAINT ck_address_road_staging_processed_at
        CHECK (
            (processing_status = 'LOADED' AND processed_at IS NULL)
            OR (processing_status IN ('VALID', 'REJECTED') AND processed_at IS NOT NULL)
        )
);

CREATE INDEX idx_address_road_staging_batch_status_row
    ON address.address_road_staging (batch_id, processing_status, source_row_number);

COMMENT ON TABLE address.address_road_staging IS
    '도로명주소 CSV를 변환 없이 보관하고 검증하는 배치 단위 원본 적재 테이블';
COMMENT ON COLUMN address.address_road_staging.source_row_number IS
    'CSV 헤더를 1행으로 포함한 원본 파일의 물리적 행 번호';
COMMENT ON COLUMN address.address_road_staging.processing_status IS
    'LOADED: 검증 전, VALID: 검증 통과, REJECTED: 검역 대상';

CREATE TABLE address.address_import_rejection (
    rejection_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL,
    source_row_number BIGINT NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    field_name VARCHAR(64),
    rejected_value TEXT,
    reason_detail VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_address_import_rejection_batch
        FOREIGN KEY (batch_id)
        REFERENCES address.address_import_batch (batch_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_address_import_rejection_reason
        UNIQUE NULLS NOT DISTINCT (batch_id, source_row_number, reason_code, field_name),
    CONSTRAINT ck_address_import_rejection_source_row
        CHECK (source_row_number > 1),
    CONSTRAINT ck_address_import_rejection_reason_code
        CHECK (btrim(reason_code) <> ''),
    CONSTRAINT ck_address_import_rejection_field_name
        CHECK (field_name IS NULL OR btrim(field_name) <> ''),
    CONSTRAINT ck_address_import_rejection_reason_detail
        CHECK (reason_detail IS NULL OR btrim(reason_detail) <> '')
);

CREATE INDEX idx_address_import_rejection_batch_row
    ON address.address_import_rejection (batch_id, source_row_number);

CREATE INDEX idx_address_import_rejection_reason_created_at
    ON address.address_import_rejection (reason_code, created_at DESC);

COMMENT ON TABLE address.address_import_rejection IS
    '공공 주소 파일 검증에서 거부된 행의 오류 코드와 필드별 원인을 기록하는 테이블';
COMMENT ON COLUMN address.address_import_rejection.field_name IS
    '행 전체 오류인 경우 NULL, 특정 컬럼 오류인 경우 CSV 컬럼명';
