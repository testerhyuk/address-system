CREATE TABLE address.api_request_replay (
    client_id VARCHAR(100) NOT NULL,
    request_id UUID NOT NULL,
    request_timestamp TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_api_request_replay
        PRIMARY KEY (client_id, request_id),
    CONSTRAINT ck_api_request_replay_client_id
        CHECK (btrim(client_id) <> ''),
    CONSTRAINT ck_api_request_replay_expiration
        CHECK (expires_at > received_at)
);

CREATE INDEX idx_api_request_replay_expiration
    ON address.api_request_replay (expires_at);

COMMENT ON TABLE address.api_request_replay IS
    'HMAC 인증 요청 ID를 만료 시각까지 보관하여 같은 요청의 재전송을 차단한다';
COMMENT ON COLUMN address.api_request_replay.request_id IS
    '외부 호출 시스템이 요청마다 생성하는 무작위 UUID이며 주문 또는 사용자 식별값을 허용하지 않는다';
