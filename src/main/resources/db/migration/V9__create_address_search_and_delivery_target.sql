CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE FUNCTION address.normalize_address_search_text(value TEXT)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT regexp_replace(lower(btrim(COALESCE(value, ''))), '[[:space:]]+', ' ', 'g')
$$;

CREATE FUNCTION address.normalize_building_dong(value TEXT)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT regexp_replace(lower(COALESCE(value, '')), '[[:space:]]+', '', 'g')
$$;

CREATE FUNCTION address.format_building_number(
    underground_flag SMALLINT,
    main_number INTEGER,
    sub_number INTEGER
)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT
        CASE underground_flag
            WHEN 1 THEN '지하 '
            WHEN 2 THEN '공중 '
            WHEN 3 THEN '수상 '
            ELSE ''
        END
        || main_number::TEXT
        || CASE WHEN sub_number = 0 THEN '' ELSE '-' || sub_number::TEXT END
$$;

CREATE FUNCTION address.format_jibun_number(main_number INTEGER, sub_number INTEGER)
RETURNS TEXT
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT main_number::TEXT
        || CASE WHEN sub_number = 0 THEN '' ELSE '-' || sub_number::TEXT END
$$;

CREATE TABLE address.delivery_target (
    delivery_target_id UUID PRIMARY KEY,
    road_address_id UUID NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    building_dong_name VARCHAR(40),
    normalized_building_dong VARCHAR(40),
    source_type VARCHAR(24) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    version_no BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    inactive_at TIMESTAMPTZ,

    CONSTRAINT fk_delivery_target_road_address
        FOREIGN KEY (road_address_id)
        REFERENCES address.road_address (road_address_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_delivery_target_identity
        UNIQUE NULLS NOT DISTINCT (
            road_address_id,
            target_type,
            normalized_building_dong
        ),
    CONSTRAINT ck_delivery_target_type
        CHECK (target_type IN ('BUILDING', 'BUILDING_DONG')),
    CONSTRAINT ck_delivery_target_dong_fields
        CHECK (
            (
                target_type = 'BUILDING'
                AND building_dong_name IS NULL
                AND normalized_building_dong IS NULL
            )
            OR (
                target_type = 'BUILDING_DONG'
                AND building_dong_name IS NOT NULL
                AND normalized_building_dong IS NOT NULL
                AND char_length(normalized_building_dong) BETWEEN 2 AND 40
                AND right(normalized_building_dong, 1) = '동'
                AND normalized_building_dong !~ '(호|층)'
                AND normalized_building_dong =
                    address.normalize_building_dong(building_dong_name)
            )
        ),
    CONSTRAINT ck_delivery_target_source_type
        CHECK (source_type IN ('ADDRESS_SELECTION', 'EXTERNAL_DELIVERY')),
    CONSTRAINT ck_delivery_target_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_delivery_target_version
        CHECK (version_no > 0),
    CONSTRAINT ck_delivery_target_inactive_at
        CHECK (
            (status = 'ACTIVE' AND inactive_at IS NULL)
            OR (status = 'INACTIVE' AND inactive_at IS NOT NULL)
        )
);

CREATE INDEX idx_delivery_target_active_address
    ON address.delivery_target (road_address_id, target_type)
    WHERE status = 'ACTIVE';

CREATE TABLE address.address_search_document (
    search_document_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    road_address_id UUID NOT NULL,
    jibun_address_id UUID,
    source_type VARCHAR(12) NOT NULL,
    display_address TEXT NOT NULL,
    normalized_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_address_search_document_road_address
        FOREIGN KEY (road_address_id)
        REFERENCES address.road_address (road_address_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_address_search_document_jibun_address
        FOREIGN KEY (jibun_address_id)
        REFERENCES address.jibun_address (jibun_address_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_address_search_document_source_type
        CHECK (source_type IN ('ROAD', 'JIBUN')),
    CONSTRAINT ck_address_search_document_source
        CHECK (
            (source_type = 'ROAD' AND jibun_address_id IS NULL)
            OR (source_type = 'JIBUN' AND jibun_address_id IS NOT NULL)
        ),
    CONSTRAINT ck_address_search_document_display
        CHECK (btrim(display_address) <> ''),
    CONSTRAINT ck_address_search_document_normalized
        CHECK (btrim(normalized_text) <> '')
);

CREATE FUNCTION address.sync_road_address_search_document()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    road_display TEXT;
BEGIN
    IF NEW.status <> 'ACTIVE' THEN
        DELETE FROM address.address_search_document
        WHERE road_address_id = NEW.road_address_id;
        RETURN NEW;
    END IF;

    road_display := concat_ws(
        ' ',
        NEW.sido,
        NULLIF(NEW.sigungu, ''),
        NEW.road_name,
        address.format_building_number(
            NEW.underground_flag,
            NEW.build_main,
            NEW.build_sub
        )
    );

    INSERT INTO address.address_search_document (
        road_address_id,
        jibun_address_id,
        source_type,
        display_address,
        normalized_text
    ) VALUES (
        NEW.road_address_id,
        NULL,
        'ROAD',
        road_display,
        address.normalize_address_search_text(
            concat_ws(
                ' ',
                road_display,
                NEW.zip_code,
                NULLIF(NEW.build_name_official, ''),
                NULLIF(NEW.build_name_sigungu, ''),
                NULLIF(NEW.b_dong_name, '')
            )
        )
    )
    ON CONFLICT (road_address_id) WHERE source_type = 'ROAD'
    DO UPDATE SET
        display_address = EXCLUDED.display_address,
        normalized_text = EXCLUDED.normalized_text,
        updated_at = CURRENT_TIMESTAMP;

    DELETE FROM address.address_search_document
    WHERE road_address_id = NEW.road_address_id
      AND source_type = 'JIBUN';

    INSERT INTO address.address_search_document (
        road_address_id,
        jibun_address_id,
        source_type,
        display_address,
        normalized_text
    )
    SELECT
        NEW.road_address_id,
        jibun.jibun_address_id,
        'JIBUN',
        concat_ws(
            ' ',
            NEW.sido,
            NULLIF(NEW.sigungu, ''),
            jibun.b_dong_name,
            NULLIF(jibun.ri_name, ''),
            address.format_jibun_number(jibun.jibun_main, jibun.jibun_sub)
        ),
        address.normalize_address_search_text(
            concat_ws(
                ' ',
                NEW.sido,
                NULLIF(NEW.sigungu, ''),
                jibun.b_dong_name,
                NULLIF(jibun.ri_name, ''),
                address.format_jibun_number(jibun.jibun_main, jibun.jibun_sub),
                NULLIF(NEW.build_name_official, ''),
                NULLIF(NEW.build_name_sigungu, '')
            )
        )
    FROM address.jibun_address jibun
    WHERE jibun.road_address_id = NEW.road_address_id
      AND jibun.status = 'ACTIVE';

    RETURN NEW;
END;
$$;

CREATE FUNCTION address.sync_jibun_address_search_document()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM address.address_search_document
    WHERE jibun_address_id = NEW.jibun_address_id;

    IF NEW.status <> 'ACTIVE' THEN
        RETURN NEW;
    END IF;

    INSERT INTO address.address_search_document (
        road_address_id,
        jibun_address_id,
        source_type,
        display_address,
        normalized_text
    )
    SELECT
        road.road_address_id,
        NEW.jibun_address_id,
        'JIBUN',
        concat_ws(
            ' ',
            road.sido,
            NULLIF(road.sigungu, ''),
            NEW.b_dong_name,
            NULLIF(NEW.ri_name, ''),
            address.format_jibun_number(NEW.jibun_main, NEW.jibun_sub)
        ),
        address.normalize_address_search_text(
            concat_ws(
                ' ',
                road.sido,
                NULLIF(road.sigungu, ''),
                NEW.b_dong_name,
                NULLIF(NEW.ri_name, ''),
                address.format_jibun_number(NEW.jibun_main, NEW.jibun_sub),
                NULLIF(road.build_name_official, ''),
                NULLIF(road.build_name_sigungu, '')
            )
        )
    FROM address.road_address road
    WHERE road.road_address_id = NEW.road_address_id
      AND road.status = 'ACTIVE';

    RETURN NEW;
END;
$$;

INSERT INTO address.address_search_document (
    road_address_id,
    jibun_address_id,
    source_type,
    display_address,
    normalized_text
)
SELECT
    road.road_address_id,
    NULL,
    'ROAD',
    concat_ws(
        ' ',
        road.sido,
        NULLIF(road.sigungu, ''),
        road.road_name,
        address.format_building_number(
            road.underground_flag,
            road.build_main,
            road.build_sub
        )
    ),
    address.normalize_address_search_text(
        concat_ws(
            ' ',
            road.sido,
            NULLIF(road.sigungu, ''),
            road.road_name,
            address.format_building_number(
                road.underground_flag,
                road.build_main,
                road.build_sub
            ),
            road.zip_code,
            NULLIF(road.build_name_official, ''),
            NULLIF(road.build_name_sigungu, ''),
            NULLIF(road.b_dong_name, '')
        )
    )
FROM address.road_address road
WHERE road.status = 'ACTIVE';

INSERT INTO address.address_search_document (
    road_address_id,
    jibun_address_id,
    source_type,
    display_address,
    normalized_text
)
SELECT
    road.road_address_id,
    jibun.jibun_address_id,
    'JIBUN',
    concat_ws(
        ' ',
        road.sido,
        NULLIF(road.sigungu, ''),
        jibun.b_dong_name,
        NULLIF(jibun.ri_name, ''),
        address.format_jibun_number(jibun.jibun_main, jibun.jibun_sub)
    ),
    address.normalize_address_search_text(
        concat_ws(
            ' ',
            road.sido,
            NULLIF(road.sigungu, ''),
            jibun.b_dong_name,
            NULLIF(jibun.ri_name, ''),
            address.format_jibun_number(jibun.jibun_main, jibun.jibun_sub),
            NULLIF(road.build_name_official, ''),
            NULLIF(road.build_name_sigungu, '')
        )
    )
FROM address.jibun_address jibun
JOIN address.road_address road
  ON road.road_address_id = jibun.road_address_id
WHERE jibun.status = 'ACTIVE'
  AND road.status = 'ACTIVE';

CREATE UNIQUE INDEX uq_address_search_document_road
    ON address.address_search_document (road_address_id)
    WHERE source_type = 'ROAD';

CREATE UNIQUE INDEX uq_address_search_document_jibun
    ON address.address_search_document (jibun_address_id)
    WHERE source_type = 'JIBUN';

CREATE INDEX idx_address_search_document_road_address
    ON address.address_search_document (road_address_id);

CREATE INDEX idx_address_search_document_trigram
    ON address.address_search_document
    USING GIN (normalized_text public.gin_trgm_ops);

CREATE TRIGGER trg_road_address_search_document
AFTER INSERT OR UPDATE ON address.road_address
FOR EACH ROW
EXECUTE FUNCTION address.sync_road_address_search_document();

CREATE TRIGGER trg_jibun_address_search_document
AFTER INSERT OR UPDATE ON address.jibun_address
FOR EACH ROW
EXECUTE FUNCTION address.sync_jibun_address_search_document();

COMMENT ON TABLE address.address_search_document IS
    '도로명주소와 지번주소를 한글 부분 검색하기 위한 비정규화 검색 문서';
COMMENT ON TABLE address.delivery_target IS
    '배달 완료 좌표를 건물 또는 건물 동 단위로 누적하기 위한 안정적인 배송 대상 식별자';
COMMENT ON COLUMN address.delivery_target.building_dong_name IS
    '101동과 같은 건물 동 정보만 저장하며 세대 호수와 상세주소는 저장하지 않음';
