ALTER TABLE address.delivery_coordinate_candidate
    DROP CONSTRAINT ck_delivery_coordinate_candidate_status;

ALTER TABLE address.delivery_coordinate_candidate
    ADD CONSTRAINT ck_delivery_coordinate_candidate_status
        CHECK (status IN (
            'GENERATED', 'REVIEW_REQUIRED', 'APPROVED',
            'REJECTED', 'PROMOTED', 'CONFIRMED'
        ));

ALTER TABLE address.delivery_coordinate_version
    ADD COLUMN source_candidate_id UUID;

ALTER TABLE address.delivery_coordinate_version
    ADD CONSTRAINT fk_delivery_coordinate_version_candidate
        FOREIGN KEY (source_candidate_id)
        REFERENCES address.delivery_coordinate_candidate (candidate_id)
        ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_delivery_coordinate_version_candidate
    ON address.delivery_coordinate_version (source_candidate_id)
    WHERE source_candidate_id IS NOT NULL;

CREATE TABLE address.delivery_coordinate_quality_evaluation (
    evaluation_id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL UNIQUE,
    run_id UUID NOT NULL,
    delivery_target_id UUID NOT NULL,
    promoted_coordinate_id UUID,
    decision VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    policy_version VARCHAR(32) NOT NULL,
    quality_score NUMERIC(5, 4) NOT NULL,
    dominance_ratio NUMERIC(5, 4) NOT NULL,
    outlier_ratio NUMERIC(5, 4) NOT NULL,
    distance_from_active_meters NUMERIC(10, 2),
    min_candidate_samples INTEGER NOT NULL,
    max_radius_meters NUMERIC(10, 2) NOT NULL,
    max_outlier_ratio NUMERIC(5, 4) NOT NULL,
    min_dominant_ratio NUMERIC(5, 4) NOT NULL,
    min_dominance_gap NUMERIC(5, 4) NOT NULL,
    min_promotion_score NUMERIC(5, 4) NOT NULL,
    max_automatic_shift_meters NUMERIC(10, 2) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_coordinate_quality_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES address.delivery_coordinate_candidate (candidate_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_coordinate_quality_run
        FOREIGN KEY (run_id)
        REFERENCES address.coordinate_analysis_run (run_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_coordinate_quality_target
        FOREIGN KEY (delivery_target_id)
        REFERENCES address.delivery_target (delivery_target_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_coordinate_quality_promoted_coordinate
        FOREIGN KEY (promoted_coordinate_id)
        REFERENCES address.delivery_coordinate_version (coordinate_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_coordinate_quality_decision
        CHECK (decision IN (
            'PROMOTED', 'CONFIRMED', 'REVIEW_REQUIRED', 'REJECTED'
        )),
    CONSTRAINT ck_coordinate_quality_scores
        CHECK (
            quality_score BETWEEN 0 AND 1
            AND dominance_ratio BETWEEN 0 AND 1
            AND outlier_ratio BETWEEN 0 AND 1
        ),
    CONSTRAINT ck_coordinate_quality_distance
        CHECK (
            distance_from_active_meters IS NULL
            OR distance_from_active_meters >= 0
        ),
    CONSTRAINT ck_coordinate_quality_policy
        CHECK (
            min_candidate_samples > 0
            AND max_radius_meters > 0
            AND max_outlier_ratio BETWEEN 0 AND 1
            AND min_dominant_ratio BETWEEN 0 AND 1
            AND min_dominance_gap BETWEEN 0 AND 1
            AND min_promotion_score BETWEEN 0 AND 1
            AND max_automatic_shift_meters > 0
        ),
    CONSTRAINT ck_coordinate_quality_promoted_link
        CHECK (
            (decision IN ('PROMOTED', 'CONFIRMED'))
            = (promoted_coordinate_id IS NOT NULL)
        )
);

CREATE INDEX idx_coordinate_quality_target_time
    ON address.delivery_coordinate_quality_evaluation (
        delivery_target_id, evaluated_at DESC
    );

CREATE INDEX idx_coordinate_candidate_review
    ON address.delivery_coordinate_candidate (created_at)
    WHERE status = 'REVIEW_REQUIRED';
