ALTER TABLE address.delivery_target
    ADD COLUMN coordinate_serving_status VARCHAR(12) NOT NULL DEFAULT 'ENABLED',
    ADD CONSTRAINT ck_delivery_target_coordinate_serving_status
        CHECK (coordinate_serving_status IN ('ENABLED', 'SUSPENDED'));

ALTER TABLE address.delivery_coordinate_candidate
    DROP CONSTRAINT ck_delivery_coordinate_candidate_status,
    ADD CONSTRAINT ck_delivery_coordinate_candidate_status
        CHECK (status IN (
            'GENERATED', 'REVIEW_REQUIRED', 'APPROVED',
            'REJECTED', 'PROMOTED', 'CONFIRMED', 'INVALIDATED'
        ));

ALTER TABLE address.coordinate_operation_audit
    DROP CONSTRAINT ck_coordinate_operation_action,
    ADD CONSTRAINT ck_coordinate_operation_action
        CHECK (action_type IN (
            'CANDIDATE_APPROVED',
            'CANDIDATE_REJECTED',
            'MANUAL_COORDINATE_ACTIVATED',
            'COORDINATE_RESTORED',
            'COORDINATE_EXCLUDED',
            'REANALYSIS_REQUESTED',
            'FAILED_SAMPLES_REQUEUED'
        ));

CREATE FUNCTION address.deactivate_retired_road_address_dependents()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    changed_at TIMESTAMPTZ := CURRENT_TIMESTAMP;
BEGIN
    IF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED' THEN
        UPDATE address.delivery_coordinate_version coordinate
           SET status = 'EXCLUDED',
               retired_at = changed_at
          FROM address.delivery_target target
         WHERE target.road_address_id = NEW.road_address_id
           AND coordinate.delivery_target_id = target.delivery_target_id
           AND coordinate.status = 'ACTIVE';

        UPDATE address.delivery_coordinate_candidate candidate
           SET status = 'INVALIDATED'
          FROM address.delivery_target target
         WHERE target.road_address_id = NEW.road_address_id
           AND candidate.delivery_target_id = target.delivery_target_id
           AND candidate.status IN ('GENERATED', 'REVIEW_REQUIRED');

        UPDATE address.coordinate_processing_failure failure
           SET status = 'RESOLVED',
               next_retry_at = NULL,
               resolved_at = changed_at
          FROM address.delivery_target target
         WHERE target.road_address_id = NEW.road_address_id
           AND failure.delivery_target_id = target.delivery_target_id
           AND failure.status <> 'RESOLVED';

        UPDATE address.address_initial_coordinate
           SET status = 'SUPERSEDED',
               superseded_at = changed_at
         WHERE road_address_id = NEW.road_address_id
           AND status = 'ACTIVE';

        DELETE FROM coordinate_raw.delivery_coordinate_sample sample
         USING address.delivery_target target
         WHERE target.road_address_id = NEW.road_address_id
           AND sample.delivery_target_id = target.delivery_target_id;

        UPDATE address.delivery_target
           SET status = 'INACTIVE',
               coordinate_serving_status = 'SUSPENDED',
               version_no = version_no + 1,
               updated_at = changed_at,
               inactive_at = changed_at
         WHERE road_address_id = NEW.road_address_id
           AND status = 'ACTIVE';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_deactivate_retired_road_address_dependents
AFTER UPDATE OF status ON address.road_address
FOR EACH ROW
EXECUTE FUNCTION address.deactivate_retired_road_address_dependents();

UPDATE address.delivery_coordinate_version coordinate
   SET status = 'EXCLUDED',
       retired_at = CURRENT_TIMESTAMP
  FROM address.delivery_target target
  JOIN address.road_address road
    ON road.road_address_id = target.road_address_id
   AND road.status = 'RETIRED'
 WHERE coordinate.delivery_target_id = target.delivery_target_id
   AND coordinate.status = 'ACTIVE';

UPDATE address.delivery_coordinate_candidate candidate
   SET status = 'INVALIDATED'
  FROM address.delivery_target target
  JOIN address.road_address road
    ON road.road_address_id = target.road_address_id
   AND road.status = 'RETIRED'
 WHERE candidate.delivery_target_id = target.delivery_target_id
   AND candidate.status IN ('GENERATED', 'REVIEW_REQUIRED');

UPDATE address.coordinate_processing_failure failure
   SET status = 'RESOLVED',
       next_retry_at = NULL,
       resolved_at = CURRENT_TIMESTAMP
  FROM address.delivery_target target
  JOIN address.road_address road
    ON road.road_address_id = target.road_address_id
   AND road.status = 'RETIRED'
 WHERE failure.delivery_target_id = target.delivery_target_id
   AND failure.status <> 'RESOLVED';

UPDATE address.address_initial_coordinate initial_coordinate
   SET status = 'SUPERSEDED',
       superseded_at = CURRENT_TIMESTAMP
  FROM address.road_address road
 WHERE road.road_address_id = initial_coordinate.road_address_id
   AND road.status = 'RETIRED'
   AND initial_coordinate.status = 'ACTIVE';

DELETE FROM coordinate_raw.delivery_coordinate_sample sample
 USING address.delivery_target target,
       address.road_address road
 WHERE road.road_address_id = target.road_address_id
   AND road.status = 'RETIRED'
   AND sample.delivery_target_id = target.delivery_target_id;

UPDATE address.delivery_target target
   SET status = 'INACTIVE',
       coordinate_serving_status = 'SUSPENDED',
       version_no = target.version_no + 1,
       updated_at = CURRENT_TIMESTAMP,
       inactive_at = CURRENT_TIMESTAMP
  FROM address.road_address road
 WHERE road.road_address_id = target.road_address_id
   AND road.status = 'RETIRED'
   AND target.status = 'ACTIVE';

COMMENT ON COLUMN address.delivery_target.coordinate_serving_status IS
    'SUSPENDED이면 GPS 분석은 유지하되 검증 좌표 자동 제공과 자동 승격을 중지한다';
