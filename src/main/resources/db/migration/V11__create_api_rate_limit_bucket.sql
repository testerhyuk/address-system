CREATE TABLE address.api_rate_limit_bucket (
    client_id VARCHAR(100) NOT NULL,
    service_key VARCHAR(40) NOT NULL,
    available_tokens NUMERIC(20, 6) NOT NULL,
    last_refill_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_api_rate_limit_bucket
        PRIMARY KEY (client_id, service_key),
    CONSTRAINT ck_api_rate_limit_bucket_client
        CHECK (btrim(client_id) <> ''),
    CONSTRAINT ck_api_rate_limit_bucket_service
        CHECK (btrim(service_key) <> ''),
    CONSTRAINT ck_api_rate_limit_bucket_tokens
        CHECK (available_tokens >= 0)
);

CREATE INDEX idx_api_rate_limit_bucket_updated_at
    ON address.api_rate_limit_bucket (updated_at);

CREATE FUNCTION address.consume_api_rate_limit_token(
    requested_client_id VARCHAR,
    requested_service_key VARCHAR,
    bucket_capacity INTEGER,
    refill_tokens_per_second NUMERIC
)
RETURNS TABLE (allowed BOOLEAN, remaining_tokens NUMERIC)
LANGUAGE plpgsql
AS $$
DECLARE
    bucket_updated_at TIMESTAMPTZ := clock_timestamp();
    calculated_tokens NUMERIC;
    stored_tokens NUMERIC;
    stored_refill_at TIMESTAMPTZ;
BEGIN
    IF bucket_capacity <= 0 OR refill_tokens_per_second <= 0 THEN
        RAISE EXCEPTION 'rate limit capacity and refill rate must be positive';
    END IF;

    INSERT INTO address.api_rate_limit_bucket (
        client_id,
        service_key,
        available_tokens,
        last_refill_at,
        updated_at
    ) VALUES (
        requested_client_id,
        requested_service_key,
        bucket_capacity - 1,
        bucket_updated_at,
        bucket_updated_at
    )
    ON CONFLICT (client_id, service_key) DO NOTHING;

    IF FOUND THEN
        RETURN QUERY SELECT TRUE, (bucket_capacity - 1)::NUMERIC;
        RETURN;
    END IF;

    SELECT bucket.available_tokens, bucket.last_refill_at
      INTO stored_tokens, stored_refill_at
      FROM address.api_rate_limit_bucket bucket
     WHERE bucket.client_id = requested_client_id
       AND bucket.service_key = requested_service_key
     FOR UPDATE;

    calculated_tokens := LEAST(
        bucket_capacity::NUMERIC,
        stored_tokens
            + GREATEST(
                0,
                EXTRACT(EPOCH FROM (bucket_updated_at - stored_refill_at))
            ) * refill_tokens_per_second
    );

    IF calculated_tokens < 1 THEN
        UPDATE address.api_rate_limit_bucket
           SET available_tokens = calculated_tokens,
               last_refill_at = bucket_updated_at,
               updated_at = bucket_updated_at
         WHERE client_id = requested_client_id
           AND service_key = requested_service_key;

        RETURN QUERY SELECT FALSE, calculated_tokens;
        RETURN;
    END IF;

    calculated_tokens := calculated_tokens - 1;
    UPDATE address.api_rate_limit_bucket
       SET available_tokens = calculated_tokens,
           last_refill_at = bucket_updated_at,
           updated_at = bucket_updated_at
     WHERE client_id = requested_client_id
       AND service_key = requested_service_key;

    RETURN QUERY SELECT TRUE, calculated_tokens;
END;
$$;

COMMENT ON TABLE address.api_rate_limit_bucket IS
    '외부 시스템 Client ID와 API 종류별 토큰만 저장하는 다중 서버 공용 호출량 제한 버킷';
COMMENT ON COLUMN address.api_rate_limit_bucket.service_key IS
    '검색어나 요청 경로 원문이 아닌 ADDRESS_SEARCH, DELIVERY_TARGET 등의 고정 API 분류값';
