ALTER TABLE address.address_import_batch
    ADD COLUMN import_mode VARCHAR(8);

UPDATE address.address_import_batch
SET import_mode = 'DELTA',
    source_reference_date = COALESCE(source_reference_date, created_at::date)
WHERE import_mode IS NULL
   OR source_reference_date IS NULL;

ALTER TABLE address.address_import_batch
    ALTER COLUMN import_mode SET NOT NULL,
    ALTER COLUMN source_reference_date SET NOT NULL,
    DROP CONSTRAINT ck_address_import_batch_status,
    ADD CONSTRAINT ck_address_import_batch_status
        CHECK (status IN (
            'REGISTERED',
            'LOADING',
            'VALIDATING',
            'APPLYING',
            'COMPLETED',
            'FAILED'
        )),
    ADD CONSTRAINT ck_address_import_batch_mode
        CHECK (import_mode IN ('FULL', 'DELTA'));

ALTER TABLE address.address_road_staging
    ADD COLUMN apply_action VARCHAR(16),
    ADD COLUMN applied_at TIMESTAMPTZ,
    DROP CONSTRAINT ck_address_road_staging_processing_status,
    DROP CONSTRAINT ck_address_road_staging_processed_at,
    ADD CONSTRAINT ck_address_road_staging_processing_status
        CHECK (processing_status IN ('LOADED', 'VALID', 'REJECTED', 'APPLIED')),
    ADD CONSTRAINT ck_address_road_staging_processed_at
        CHECK (
            (processing_status = 'LOADED' AND processed_at IS NULL)
            OR (processing_status IN ('VALID', 'REJECTED', 'APPLIED') AND processed_at IS NOT NULL)
        ),
    ADD CONSTRAINT ck_address_road_staging_apply_result
        CHECK (
            (
                processing_status = 'APPLIED'
                AND applied_at IS NOT NULL
                AND apply_action IN (
                    'CREATED',
                    'UPDATED',
                    'RETIRED',
                    'REACTIVATED',
                    'NO_CHANGE'
                )
            )
            OR (
                processing_status <> 'APPLIED'
                AND applied_at IS NULL
                AND apply_action IS NULL
            )
        );

CREATE TABLE address.road_address (
    road_address_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mgmt_num CHAR(26) NOT NULL,
    legal_dong_code CHAR(10) NOT NULL,
    sido VARCHAR(40) NOT NULL,
    sigungu VARCHAR(40),
    b_dong_name VARCHAR(40),
    road_code CHAR(12) NOT NULL,
    road_name VARCHAR(80) NOT NULL,
    underground_flag SMALLINT NOT NULL,
    build_main INTEGER NOT NULL,
    build_sub INTEGER NOT NULL,
    zip_code CHAR(5) NOT NULL,
    apartment BOOLEAN NOT NULL,
    build_name_official VARCHAR(400),
    build_name_sigungu VARCHAR(400),
    status VARCHAR(12) NOT NULL,
    effective_date DATE NOT NULL,
    last_movement_reason_code CHAR(2),
    version_no BIGINT NOT NULL DEFAULT 1,
    source_batch_id UUID NOT NULL,
    source_row_number BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at TIMESTAMPTZ,

    CONSTRAINT uq_road_address_mgmt_num
        UNIQUE (mgmt_num),
    CONSTRAINT fk_road_address_source_batch
        FOREIGN KEY (source_batch_id)
        REFERENCES address.address_import_batch (batch_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_road_address_mgmt_num
        CHECK (mgmt_num ~ '^[0-9]{26}$'),
    CONSTRAINT ck_road_address_legal_dong_code
        CHECK (legal_dong_code ~ '^[0-9]{10}$'),
    CONSTRAINT ck_road_address_road_code
        CHECK (road_code ~ '^[0-9]{12}$'),
    CONSTRAINT ck_road_address_underground_flag
        CHECK (underground_flag BETWEEN 0 AND 3),
    CONSTRAINT ck_road_address_build_main
        CHECK (build_main BETWEEN 0 AND 99999),
    CONSTRAINT ck_road_address_build_sub
        CHECK (build_sub BETWEEN 0 AND 99999),
    CONSTRAINT ck_road_address_zip_code
        CHECK (zip_code ~ '^[0-9]{5}$'),
    CONSTRAINT ck_road_address_status
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_road_address_movement_reason
        CHECK (
            last_movement_reason_code IS NULL
            OR last_movement_reason_code IN ('31', '34', '63')
        ),
    CONSTRAINT ck_road_address_version
        CHECK (version_no > 0),
    CONSTRAINT ck_road_address_source_row
        CHECK (source_row_number > 1),
    CONSTRAINT ck_road_address_retired_at
        CHECK (
            (status = 'ACTIVE' AND retired_at IS NULL)
            OR (status = 'RETIRED' AND retired_at IS NOT NULL)
        )
);

CREATE INDEX idx_road_address_lookup
    ON address.road_address (
        road_code,
        underground_flag,
        build_main,
        build_sub
    );

CREATE INDEX idx_road_address_active_region
    ON address.road_address (legal_dong_code, road_name)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_road_address_source_batch
    ON address.road_address (source_batch_id, source_row_number);

CREATE TABLE address.road_address_history (
    history_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    road_address_id UUID NOT NULL,
    version_no BIGINT NOT NULL,
    change_type VARCHAR(16) NOT NULL,
    mgmt_num CHAR(26) NOT NULL,
    legal_dong_code CHAR(10) NOT NULL,
    sido VARCHAR(40) NOT NULL,
    sigungu VARCHAR(40),
    b_dong_name VARCHAR(40),
    road_code CHAR(12) NOT NULL,
    road_name VARCHAR(80) NOT NULL,
    underground_flag SMALLINT NOT NULL,
    build_main INTEGER NOT NULL,
    build_sub INTEGER NOT NULL,
    zip_code CHAR(5) NOT NULL,
    apartment BOOLEAN NOT NULL,
    build_name_official VARCHAR(400),
    build_name_sigungu VARCHAR(400),
    status VARCHAR(12) NOT NULL,
    effective_date DATE NOT NULL,
    movement_reason_code CHAR(2),
    source_batch_id UUID NOT NULL,
    source_row_number BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_road_address_history_version
        UNIQUE (road_address_id, version_no),
    CONSTRAINT uq_road_address_history_source_row
        UNIQUE (source_batch_id, source_row_number),
    CONSTRAINT fk_road_address_history_address
        FOREIGN KEY (road_address_id)
        REFERENCES address.road_address (road_address_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_road_address_history_source_batch
        FOREIGN KEY (source_batch_id)
        REFERENCES address.address_import_batch (batch_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_road_address_history_change_type
        CHECK (change_type IN ('CREATED', 'UPDATED', 'RETIRED', 'REACTIVATED')),
    CONSTRAINT ck_road_address_history_version
        CHECK (version_no > 0),
    CONSTRAINT ck_road_address_history_status
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_road_address_history_source_row
        CHECK (source_row_number > 1)
);

CREATE INDEX idx_road_address_history_address_recorded
    ON address.road_address_history (road_address_id, recorded_at DESC);

CREATE INDEX idx_road_address_history_batch_row
    ON address.road_address_history (source_batch_id, source_row_number);

CREATE FUNCTION address.record_road_address_history()
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
    ELSIF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED' THEN
        change_kind := 'RETIRED';
    ELSE
        change_kind := 'UPDATED';
    END IF;

    INSERT INTO address.road_address_history (
        road_address_id,
        version_no,
        change_type,
        mgmt_num,
        legal_dong_code,
        sido,
        sigungu,
        b_dong_name,
        road_code,
        road_name,
        underground_flag,
        build_main,
        build_sub,
        zip_code,
        apartment,
        build_name_official,
        build_name_sigungu,
        status,
        effective_date,
        movement_reason_code,
        source_batch_id,
        source_row_number
    ) VALUES (
        NEW.road_address_id,
        NEW.version_no,
        change_kind,
        NEW.mgmt_num,
        NEW.legal_dong_code,
        NEW.sido,
        NEW.sigungu,
        NEW.b_dong_name,
        NEW.road_code,
        NEW.road_name,
        NEW.underground_flag,
        NEW.build_main,
        NEW.build_sub,
        NEW.zip_code,
        NEW.apartment,
        NEW.build_name_official,
        NEW.build_name_sigungu,
        NEW.status,
        NEW.effective_date,
        NEW.last_movement_reason_code,
        NEW.source_batch_id,
        NEW.source_row_number
    );

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_road_address_history
AFTER INSERT OR UPDATE ON address.road_address
FOR EACH ROW
EXECUTE FUNCTION address.record_road_address_history();

COMMENT ON TABLE address.road_address IS
    '검증된 공공 도로명주소의 현재 운영 상태를 보관하는 원장';

COMMENT ON TABLE address.road_address_history IS
    '운영 도로명주소의 버전별 변경 결과와 출처를 보관하는 이력';

COMMENT ON COLUMN address.address_import_batch.import_mode IS
    'FULL: 최초 전체 구축, DELTA: 신규·수정·폐지 변동분 반영';

COMMENT ON COLUMN address.address_road_staging.apply_action IS
    '운영 주소 반영 결과. 상태 변경이 없으면 NO_CHANGE';
