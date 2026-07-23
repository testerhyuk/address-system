CREATE TABLE address.coordinate_analysis_run (
    run_id UUID PRIMARY KEY,
    delivery_target_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    eps_meters NUMERIC(8, 2) NOT NULL,
    min_points INTEGER NOT NULL,
    sample_count INTEGER NOT NULL,
    cluster_count INTEGER,
    outlier_count INTEGER,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    failure_code VARCHAR(64),

    CONSTRAINT fk_coordinate_analysis_target
        FOREIGN KEY (delivery_target_id)
        REFERENCES address.delivery_target (delivery_target_id),
    CONSTRAINT ck_coordinate_analysis_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_coordinate_analysis_parameters
        CHECK (eps_meters > 0 AND min_points > 0 AND sample_count >= 0),
    CONSTRAINT ck_coordinate_analysis_completion
        CHECK ((status = 'RUNNING') = (completed_at IS NULL)),
    CONSTRAINT ck_coordinate_analysis_failure
        CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);

CREATE TABLE coordinate_raw.delivery_coordinate_analysis_assignment (
    run_id UUID NOT NULL,
    sample_id UUID NOT NULL,
    cluster_no INTEGER,
    is_outlier BOOLEAN NOT NULL,

    PRIMARY KEY (run_id, sample_id),
    CONSTRAINT fk_coordinate_assignment_run
        FOREIGN KEY (run_id) REFERENCES address.coordinate_analysis_run (run_id),
    CONSTRAINT fk_coordinate_assignment_sample
        FOREIGN KEY (sample_id)
        REFERENCES coordinate_raw.delivery_coordinate_sample (sample_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_coordinate_assignment_outlier
        CHECK (is_outlier = (cluster_no IS NULL))
);

CREATE TABLE address.delivery_coordinate_candidate (
    candidate_id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    delivery_target_id UUID NOT NULL,
    cluster_no INTEGER NOT NULL,
    location GEOGRAPHY(POINT, 4326) NOT NULL,
    sample_count INTEGER NOT NULL,
    radius_meters NUMERIC(10, 2) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'GENERATED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_delivery_coordinate_candidate_run
        FOREIGN KEY (run_id) REFERENCES address.coordinate_analysis_run (run_id),
    CONSTRAINT fk_delivery_coordinate_candidate_target
        FOREIGN KEY (delivery_target_id)
        REFERENCES address.delivery_target (delivery_target_id),
    CONSTRAINT uq_delivery_coordinate_candidate_cluster
        UNIQUE (run_id, cluster_no),
    CONSTRAINT ck_delivery_coordinate_candidate_values
        CHECK (sample_count > 0 AND radius_meters >= 0),
    CONSTRAINT ck_delivery_coordinate_candidate_status
        CHECK (status IN ('GENERATED', 'APPROVED', 'REJECTED', 'PROMOTED'))
);

CREATE INDEX idx_coordinate_analysis_target
    ON address.coordinate_analysis_run (delivery_target_id, started_at DESC);
CREATE INDEX idx_coordinate_candidate_target
    ON address.delivery_coordinate_candidate (delivery_target_id, status);
CREATE INDEX idx_coordinate_candidate_location
    ON address.delivery_coordinate_candidate USING GIST (location);
