CREATE TABLE address.coordinate_operation_audit (
    audit_id UUID PRIMARY KEY,
    action_type VARCHAR(40) NOT NULL,
    actor_client_id VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    delivery_target_id UUID NOT NULL,
    candidate_id UUID,
    coordinate_id UUID,
    source_coordinate_id UUID,
    affected_sample_count INTEGER,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_coordinate_operation_target
        FOREIGN KEY (delivery_target_id)
        REFERENCES address.delivery_target (delivery_target_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_coordinate_operation_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES address.delivery_coordinate_candidate (candidate_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_coordinate_operation_coordinate
        FOREIGN KEY (coordinate_id)
        REFERENCES address.delivery_coordinate_version (coordinate_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_coordinate_operation_source_coordinate
        FOREIGN KEY (source_coordinate_id)
        REFERENCES address.delivery_coordinate_version (coordinate_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_coordinate_operation_action
        CHECK (action_type IN (
            'CANDIDATE_APPROVED',
            'CANDIDATE_REJECTED',
            'MANUAL_COORDINATE_ACTIVATED',
            'COORDINATE_RESTORED',
            'REANALYSIS_REQUESTED',
            'FAILED_SAMPLES_REQUEUED'
        )),
    CONSTRAINT ck_coordinate_operation_actor
        CHECK (char_length(trim(actor_client_id)) BETWEEN 1 AND 100),
    CONSTRAINT ck_coordinate_operation_reason
        CHECK (char_length(trim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_coordinate_operation_sample_count
        CHECK (affected_sample_count IS NULL OR affected_sample_count >= 0)
);

CREATE INDEX idx_coordinate_operation_target_time
    ON address.coordinate_operation_audit (
        delivery_target_id, occurred_at DESC
    );

CREATE INDEX idx_coordinate_operation_actor_time
    ON address.coordinate_operation_audit (actor_client_id, occurred_at DESC);
