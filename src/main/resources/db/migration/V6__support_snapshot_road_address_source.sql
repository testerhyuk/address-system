ALTER TABLE address.address_road_staging
    ADD COLUMN legal_area_code TEXT;

UPDATE address.address_road_staging
SET legal_area_code = substring(btrim(mgmt_num) FROM 1 FOR 8)
WHERE mgmt_num ~ '^[0-9]{26}$';

ALTER TABLE address.road_address
    ADD COLUMN legal_area_code CHAR(8),
    ADD COLUMN apartment_status VARCHAR(16);

ALTER TABLE address.road_address_history
    ADD COLUMN legal_area_code CHAR(8),
    ADD COLUMN apartment_status VARCHAR(16);

ALTER TABLE address.road_address
    DISABLE TRIGGER trg_road_address_history;

UPDATE address.road_address
SET legal_area_code = substring(mgmt_num FROM 1 FOR 8),
    apartment_status = CASE
        WHEN apartment THEN 'APARTMENT'
        ELSE 'NON_APARTMENT'
    END;

UPDATE address.road_address_history
SET legal_area_code = substring(mgmt_num FROM 1 FOR 8),
    apartment_status = CASE
        WHEN apartment THEN 'APARTMENT'
        ELSE 'NON_APARTMENT'
    END;

ALTER TABLE address.road_address
    ALTER COLUMN legal_area_code SET NOT NULL,
    ALTER COLUMN legal_dong_code DROP NOT NULL,
    ALTER COLUMN apartment_status SET NOT NULL,
    ADD CONSTRAINT ck_road_address_legal_area_code
        CHECK (legal_area_code ~ '^[0-9]{8}$'),
    ADD CONSTRAINT ck_road_address_apartment_status
        CHECK (apartment_status IN ('UNKNOWN', 'APARTMENT', 'NON_APARTMENT'));

ALTER TABLE address.road_address_history
    ALTER COLUMN legal_area_code SET NOT NULL,
    ALTER COLUMN legal_dong_code DROP NOT NULL,
    ALTER COLUMN apartment_status SET NOT NULL,
    ADD CONSTRAINT ck_road_address_history_legal_area_code
        CHECK (legal_area_code ~ '^[0-9]{8}$'),
    ADD CONSTRAINT ck_road_address_history_apartment_status
        CHECK (apartment_status IN ('UNKNOWN', 'APARTMENT', 'NON_APARTMENT'));

DROP INDEX address.idx_road_address_active_region;

CREATE INDEX idx_road_address_active_region
    ON address.road_address (legal_area_code, road_name)
    WHERE status = 'ACTIVE';

CREATE OR REPLACE FUNCTION address.record_road_address_history()
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
        legal_area_code,
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
        apartment_status,
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
        NEW.legal_area_code,
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
        NEW.apartment_status,
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

ALTER TABLE address.road_address
    DROP COLUMN apartment;

ALTER TABLE address.road_address_history
    DROP COLUMN apartment;

ALTER TABLE address.road_address
    ENABLE TRIGGER trg_road_address_history;

COMMENT ON COLUMN address.address_road_staging.legal_area_code IS
    '도로명주소관리번호에서 추출한 시군구·읍면동 8자리 코드';
COMMENT ON COLUMN address.road_address.legal_area_code IS
    '도로명주소관리번호에서 추출한 시군구·읍면동 8자리 코드';
COMMENT ON COLUMN address.road_address.legal_dong_code IS
    '공공 원본에서 제공된 경우에만 저장하는 리 코드 포함 10자리 법정동코드';
COMMENT ON COLUMN address.road_address.apartment_status IS
    'UNKNOWN: 미확보, APARTMENT: 공동주택, NON_APARTMENT: 비공동주택';
