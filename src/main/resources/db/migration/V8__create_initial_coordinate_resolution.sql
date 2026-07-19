CREATE TABLE address.address_geocoding_attempt (
    attempt_id UUID PRIMARY KEY,
    road_address_id UUID NOT NULL,
    status VARCHAR(24) NOT NULL,
    query_sido VARCHAR(40) NOT NULL,
    query_sigungu VARCHAR(40),
    query_road_name VARCHAR(80) NOT NULL,
    query_building_number VARCHAR(16) NOT NULL,
    query_zip_code CHAR(5),
    candidate_count INTEGER NOT NULL DEFAULT 0,
    decision_reason_code VARCHAR(64),
    nominatim_data_updated TIMESTAMPTZ,
    nominatim_software_version VARCHAR(32),
    retry_at TIMESTAMPTZ,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_address_geocoding_attempt_road_address
        FOREIGN KEY (road_address_id)
        REFERENCES address.road_address (road_address_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_address_geocoding_attempt_status
        CHECK (status IN (
            'MATCHED',
            'NOT_FOUND',
            'AMBIGUOUS',
            'RETRYABLE_FAILED',
            'FAILED'
        )),
    CONSTRAINT ck_address_geocoding_attempt_building_number
        CHECK (btrim(query_building_number) <> ''),
    CONSTRAINT ck_address_geocoding_attempt_candidate_count
        CHECK (candidate_count >= 0),
    CONSTRAINT ck_address_geocoding_attempt_failure_reason
        CHECK (
            status NOT IN ('RETRYABLE_FAILED', 'FAILED')
            OR decision_reason_code IS NOT NULL
        ),
    CONSTRAINT ck_address_geocoding_attempt_retry_at
        CHECK (
            (status = 'RETRYABLE_FAILED' AND retry_at IS NOT NULL)
            OR (status <> 'RETRYABLE_FAILED' AND retry_at IS NULL)
        )
);

CREATE INDEX idx_address_geocoding_attempt_address_attempted
    ON address.address_geocoding_attempt (road_address_id, attempted_at DESC);

CREATE INDEX idx_address_geocoding_attempt_retry
    ON address.address_geocoding_attempt (retry_at)
    WHERE status = 'RETRYABLE_FAILED';

CREATE INDEX idx_address_geocoding_attempt_source_version
    ON address.address_geocoding_attempt (
        road_address_id,
        nominatim_data_updated,
        attempted_at DESC
    )
    WHERE status IN ('MATCHED', 'NOT_FOUND', 'AMBIGUOUS');

CREATE TABLE address.address_geocoding_candidate (
    candidate_id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL,
    candidate_order INTEGER NOT NULL,
    place_id BIGINT NOT NULL,
    osm_type VARCHAR(16),
    osm_id BIGINT,
    category VARCHAR(64),
    candidate_type VARCHAR(64),
    place_rank SMALLINT NOT NULL,
    location GEOGRAPHY(POINT, 4326) NOT NULL,
    display_name TEXT NOT NULL,
    country_code VARCHAR(2),
    road_name VARCHAR(160),
    house_number VARCHAR(32),
    postcode VARCHAR(16),
    address_details JSONB NOT NULL,
    match_status VARCHAR(16) NOT NULL,
    match_reason_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_address_geocoding_candidate_attempt
        FOREIGN KEY (attempt_id)
        REFERENCES address.address_geocoding_attempt (attempt_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_address_geocoding_candidate_order
        UNIQUE (attempt_id, candidate_order),
    CONSTRAINT ck_address_geocoding_candidate_order
        CHECK (candidate_order > 0),
    CONSTRAINT ck_address_geocoding_candidate_place_id
        CHECK (place_id > 0),
    CONSTRAINT ck_address_geocoding_candidate_place_rank
        CHECK (place_rank BETWEEN 0 AND 30),
    CONSTRAINT ck_address_geocoding_candidate_longitude
        CHECK (ST_X(location::geometry) BETWEEN -180 AND 180),
    CONSTRAINT ck_address_geocoding_candidate_latitude
        CHECK (ST_Y(location::geometry) BETWEEN -90 AND 90),
    CONSTRAINT ck_address_geocoding_candidate_match_status
        CHECK (match_status IN ('ELIGIBLE', 'REJECTED')),
    CONSTRAINT ck_address_geocoding_candidate_match_reason
        CHECK (btrim(match_reason_code) <> '')
);

CREATE INDEX idx_address_geocoding_candidate_attempt
    ON address.address_geocoding_candidate (attempt_id, candidate_order);

CREATE INDEX idx_address_geocoding_candidate_location
    ON address.address_geocoding_candidate
    USING GIST (location);

CREATE TABLE address.address_initial_coordinate (
    initial_coordinate_id UUID PRIMARY KEY,
    road_address_id UUID NOT NULL,
    version_no BIGINT NOT NULL,
    location GEOGRAPHY(POINT, 4326) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_candidate_id UUID NOT NULL,
    source_data_updated TIMESTAMPTZ NOT NULL,
    source_software_version VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at TIMESTAMPTZ,

    CONSTRAINT fk_address_initial_coordinate_road_address
        FOREIGN KEY (road_address_id)
        REFERENCES address.road_address (road_address_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_address_initial_coordinate_candidate
        FOREIGN KEY (source_candidate_id)
        REFERENCES address.address_geocoding_candidate (candidate_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_address_initial_coordinate_version
        UNIQUE (road_address_id, version_no),
    CONSTRAINT uq_address_initial_coordinate_candidate
        UNIQUE (source_candidate_id),
    CONSTRAINT ck_address_initial_coordinate_version
        CHECK (version_no > 0),
    CONSTRAINT ck_address_initial_coordinate_longitude
        CHECK (ST_X(location::geometry) BETWEEN -180 AND 180),
    CONSTRAINT ck_address_initial_coordinate_latitude
        CHECK (ST_Y(location::geometry) BETWEEN -90 AND 90),
    CONSTRAINT ck_address_initial_coordinate_source_type
        CHECK (source_type = 'OSM_NOMINATIM'),
    CONSTRAINT ck_address_initial_coordinate_status
        CHECK (status IN ('ACTIVE', 'SUPERSEDED')),
    CONSTRAINT ck_address_initial_coordinate_superseded_at
        CHECK (
            (status = 'ACTIVE' AND superseded_at IS NULL)
            OR (status = 'SUPERSEDED' AND superseded_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_address_initial_coordinate_active
    ON address.address_initial_coordinate (road_address_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_address_initial_coordinate_location
    ON address.address_initial_coordinate
    USING GIST (location);

COMMENT ON TABLE address.address_geocoding_attempt IS
    '운영 도로명주소의 초기 대표 좌표를 찾기 위해 수행한 Nominatim 검색과 판정 결과';
COMMENT ON TABLE address.address_geocoding_candidate IS
    'Nominatim 검색 후보 원본과 자동 일치 판정 결과';
COMMENT ON TABLE address.address_initial_coordinate IS
    '검증된 배송 좌표가 생기기 전 사용하는 버전형 초기 대표 좌표';
COMMENT ON COLUMN address.address_geocoding_candidate.place_id IS
    'Nominatim 내부 진단용 식별값이며 영구 출처 식별자로 사용하지 않음';
COMMENT ON COLUMN address.address_initial_coordinate.location IS
    'Nominatim이 반환한 OSM 객체 대표점이며 검증된 배송 접근 좌표가 아님';
