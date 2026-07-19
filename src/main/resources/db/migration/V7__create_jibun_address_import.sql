CREATE TABLE address.address_jibun_staging (
    batch_id UUID NOT NULL,
    source_row_number BIGINT NOT NULL,
    mgmt_num TEXT,
    b_dong_name TEXT,
    ri_name TEXT,
    jibun_main TEXT,
    jibun_sub TEXT,
    processing_status VARCHAR(16) NOT NULL DEFAULT 'LOADED',
    processed_at TIMESTAMPTZ,
    apply_action VARCHAR(16),
    applied_at TIMESTAMPTZ,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_address_jibun_staging
        PRIMARY KEY (batch_id, source_row_number),
    CONSTRAINT fk_address_jibun_staging_batch
        FOREIGN KEY (batch_id)
        REFERENCES address.address_import_batch (batch_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_address_jibun_staging_source_row
        CHECK (source_row_number > 1),
    CONSTRAINT ck_address_jibun_staging_processing_status
        CHECK (processing_status IN ('LOADED', 'VALID', 'REJECTED', 'APPLIED')),
    CONSTRAINT ck_address_jibun_staging_processed_at
        CHECK (
            (processing_status = 'LOADED' AND processed_at IS NULL)
            OR (processing_status IN ('VALID', 'REJECTED', 'APPLIED') AND processed_at IS NOT NULL)
        ),
    CONSTRAINT ck_address_jibun_staging_apply_result
        CHECK (
            (
                processing_status = 'APPLIED'
                AND applied_at IS NOT NULL
                AND apply_action IN ('CREATED', 'REACTIVATED', 'NO_CHANGE')
            )
            OR (
                processing_status <> 'APPLIED'
                AND applied_at IS NULL
                AND apply_action IS NULL
            )
        )
);

CREATE INDEX idx_address_jibun_staging_batch_status_row
    ON address.address_jibun_staging (batch_id, processing_status, source_row_number);

CREATE INDEX idx_address_jibun_staging_batch_key
    ON address.address_jibun_staging (
        batch_id,
        mgmt_num,
        b_dong_name,
        ri_name,
        jibun_main,
        jibun_sub
    );

CREATE TABLE address.jibun_address (
    jibun_address_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    road_address_id UUID NOT NULL,
    mgmt_num CHAR(26) NOT NULL,
    legal_area_code CHAR(8) NOT NULL,
    b_dong_name VARCHAR(40) NOT NULL,
    ri_name VARCHAR(40),
    jibun_main INTEGER NOT NULL,
    jibun_sub INTEGER NOT NULL,
    status VARCHAR(12) NOT NULL,
    version_no BIGINT NOT NULL DEFAULT 1,
    source_batch_id UUID NOT NULL,
    source_row_number BIGINT,
    source_reference_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at TIMESTAMPTZ,

    CONSTRAINT uq_jibun_address_source_key
        UNIQUE NULLS NOT DISTINCT (
            mgmt_num,
            b_dong_name,
            ri_name,
            jibun_main,
            jibun_sub
        ),
    CONSTRAINT fk_jibun_address_road_address
        FOREIGN KEY (road_address_id)
        REFERENCES address.road_address (road_address_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_jibun_address_source_batch
        FOREIGN KEY (source_batch_id)
        REFERENCES address.address_import_batch (batch_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_jibun_address_mgmt_num
        CHECK (mgmt_num ~ '^[0-9]{26}$'),
    CONSTRAINT ck_jibun_address_legal_area_code
        CHECK (legal_area_code ~ '^[0-9]{8}$'),
    CONSTRAINT ck_jibun_address_main
        CHECK (jibun_main BETWEEN 0 AND 9999),
    CONSTRAINT ck_jibun_address_sub
        CHECK (jibun_sub BETWEEN 0 AND 9999),
    CONSTRAINT ck_jibun_address_status
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_jibun_address_version
        CHECK (version_no > 0),
    CONSTRAINT ck_jibun_address_source_row
        CHECK (source_row_number IS NULL OR source_row_number > 1),
    CONSTRAINT ck_jibun_address_retired_at
        CHECK (
            (status = 'ACTIVE' AND retired_at IS NULL AND source_row_number IS NOT NULL)
            OR (status = 'RETIRED' AND retired_at IS NOT NULL AND source_row_number IS NULL)
        )
);

CREATE INDEX idx_jibun_address_active_search
    ON address.jibun_address (
        legal_area_code,
        b_dong_name,
        ri_name,
        jibun_main,
        jibun_sub
    )
    WHERE status = 'ACTIVE';

CREATE INDEX idx_jibun_address_road_address
    ON address.jibun_address (road_address_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_jibun_address_source_batch
    ON address.jibun_address (source_batch_id, source_row_number);

CREATE TABLE address.jibun_address_history (
    history_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    jibun_address_id UUID NOT NULL,
    version_no BIGINT NOT NULL,
    change_type VARCHAR(16) NOT NULL,
    road_address_id UUID NOT NULL,
    mgmt_num CHAR(26) NOT NULL,
    legal_area_code CHAR(8) NOT NULL,
    b_dong_name VARCHAR(40) NOT NULL,
    ri_name VARCHAR(40),
    jibun_main INTEGER NOT NULL,
    jibun_sub INTEGER NOT NULL,
    status VARCHAR(12) NOT NULL,
    source_batch_id UUID NOT NULL,
    source_row_number BIGINT,
    source_reference_date DATE NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_jibun_address_history_version
        UNIQUE (jibun_address_id, version_no),
    CONSTRAINT fk_jibun_address_history_address
        FOREIGN KEY (jibun_address_id)
        REFERENCES address.jibun_address (jibun_address_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_jibun_address_history_road_address
        FOREIGN KEY (road_address_id)
        REFERENCES address.road_address (road_address_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_jibun_address_history_source_batch
        FOREIGN KEY (source_batch_id)
        REFERENCES address.address_import_batch (batch_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_jibun_address_history_change_type
        CHECK (change_type IN ('CREATED', 'REACTIVATED', 'RETIRED')),
    CONSTRAINT ck_jibun_address_history_status
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_jibun_address_history_version
        CHECK (version_no > 0),
    CONSTRAINT ck_jibun_address_history_source_row
        CHECK (source_row_number IS NULL OR source_row_number > 1)
);

CREATE INDEX idx_jibun_address_history_address_recorded
    ON address.jibun_address_history (jibun_address_id, recorded_at DESC);

CREATE INDEX idx_jibun_address_history_batch
    ON address.jibun_address_history (source_batch_id, source_row_number);

CREATE FUNCTION address.record_jibun_address_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    change_kind VARCHAR(16);
BEGIN
    IF TG_OP = 'INSERT' THEN
        change_kind := 'CREATED';
    ELSIF OLD.status = 'RETIRED' AND NEW.status = 'ACTIVE' THEN
        change_kind := 'REACTIVATED';
    ELSE
        change_kind := 'RETIRED';
    END IF;

    INSERT INTO address.jibun_address_history (
        jibun_address_id,
        version_no,
        change_type,
        road_address_id,
        mgmt_num,
        legal_area_code,
        b_dong_name,
        ri_name,
        jibun_main,
        jibun_sub,
        status,
        source_batch_id,
        source_row_number,
        source_reference_date
    ) VALUES (
        NEW.jibun_address_id,
        NEW.version_no,
        change_kind,
        NEW.road_address_id,
        NEW.mgmt_num,
        NEW.legal_area_code,
        NEW.b_dong_name,
        NEW.ri_name,
        NEW.jibun_main,
        NEW.jibun_sub,
        NEW.status,
        NEW.source_batch_id,
        NEW.source_row_number,
        NEW.source_reference_date
    );

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_jibun_address_history
AFTER INSERT OR UPDATE ON address.jibun_address
FOR EACH ROW
EXECUTE FUNCTION address.record_jibun_address_history();

COMMENT ON TABLE address.address_jibun_staging IS
    '지번 CSV 원문과 검증·운영 반영 상태를 배치 및 원본 행 단위로 보관';
COMMENT ON TABLE address.jibun_address IS
    '도로명주소 관리번호에 연결된 현재 운영 지번 별칭';
COMMENT ON TABLE address.jibun_address_history IS
    '지번 별칭의 생성·재활성화·폐지 버전 이력';
COMMENT ON COLUMN address.jibun_address.legal_area_code IS
    '관리번호 앞 8자리에서 추출한 법정구역 코드. 10자리 법정동코드는 원본에 없어 저장하지 않음';
