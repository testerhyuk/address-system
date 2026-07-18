ALTER TABLE address.address_road_staging
    ADD COLUMN legal_dong_code TEXT,
    ADD COLUMN road_code TEXT,
    ADD COLUMN underground_flag TEXT,
    ADD COLUMN effective_date TEXT,
    ADD COLUMN apartment_flag TEXT,
    ADD COLUMN movement_reason_code TEXT;

COMMENT ON COLUMN address.address_road_staging.legal_dong_code IS
    '법정동을 안정적으로 식별하기 위한 공공 주소 원본 코드';
COMMENT ON COLUMN address.address_road_staging.road_code IS
    '동일한 도로명을 구분하기 위한 공공 주소 원본 도로명코드';
COMMENT ON COLUMN address.address_road_staging.underground_flag IS
    '지상, 지하, 공중 및 수상 주소를 구분하는 공공 주소 원본 값';
COMMENT ON COLUMN address.address_road_staging.effective_date IS
    '주소 변경의 효력발생일 원본 값';
COMMENT ON COLUMN address.address_road_staging.apartment_flag IS
    '공동주택 여부를 판별하기 위한 공공 주소 원본 값';
COMMENT ON COLUMN address.address_road_staging.movement_reason_code IS
    '신규, 수정 및 폐지를 구분하는 공공 주소 원본 코드';
