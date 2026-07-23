package com.address.address_system.address.coordinate.failure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoordinateProcessingFailureRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoordinateProcessingFailureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<FailureState> lock(
            String stage,
            UUID targetId,
            UUID candidateId
    ) {
        return jdbcTemplate.query(
                """
                SELECT failure_id, status, attempt_count
                  FROM address.coordinate_processing_failure
                 WHERE stage = ?
                   AND delivery_target_id = ?
                   AND candidate_id IS NOT DISTINCT FROM ?
                   FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new FailureState(
                                resultSet.getObject("failure_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getInt("attempt_count")
                        ))
                        : Optional.empty(),
                stage,
                targetId,
                candidateId
        );
    }

    public Optional<UUID> findCandidateTarget(UUID candidateId) {
        return jdbcTemplate.query(
                """
                SELECT delivery_target_id
                  FROM address.delivery_coordinate_candidate
                 WHERE candidate_id = ?
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(resultSet.getObject(1, UUID.class))
                        : Optional.empty(),
                candidateId
        );
    }

    public void insert(
            UUID failureId,
            String stage,
            UUID targetId,
            UUID candidateId,
            String status,
            int attemptCount,
            String failureCode,
            Instant failedAt,
            Instant nextRetryAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO address.coordinate_processing_failure (
                    failure_id, stage, delivery_target_id, candidate_id,
                    status, attempt_count, failure_code, first_failed_at,
                    last_failed_at, next_retry_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                failureId,
                stage,
                targetId,
                candidateId,
                status,
                attemptCount,
                failureCode,
                Timestamp.from(failedAt),
                Timestamp.from(failedAt),
                timestamp(nextRetryAt)
        );
    }

    public void restartResolved(
            UUID failureId,
            String status,
            String failureCode,
            Instant failedAt,
            Instant nextRetryAt
    ) {
        jdbcTemplate.update(
                """
                UPDATE address.coordinate_processing_failure
                   SET status = ?, attempt_count = 1, failure_code = ?,
                       first_failed_at = ?, last_failed_at = ?,
                       next_retry_at = ?, resolved_at = NULL
                 WHERE failure_id = ?
                """,
                status,
                failureCode,
                Timestamp.from(failedAt),
                Timestamp.from(failedAt),
                timestamp(nextRetryAt),
                failureId
        );
    }

    public void updateFailure(
            UUID failureId,
            String status,
            int attemptCount,
            String failureCode,
            Instant failedAt,
            Instant nextRetryAt
    ) {
        jdbcTemplate.update(
                """
                UPDATE address.coordinate_processing_failure
                   SET status = ?, attempt_count = ?, failure_code = ?,
                       last_failed_at = ?, next_retry_at = ?, resolved_at = NULL
                 WHERE failure_id = ?
                """,
                status,
                attemptCount,
                failureCode,
                Timestamp.from(failedAt),
                timestamp(nextRetryAt),
                failureId
        );
    }

    public void resolve(
            String stage,
            UUID targetId,
            UUID candidateId,
            Instant resolvedAt
    ) {
        jdbcTemplate.update(
                """
                UPDATE address.coordinate_processing_failure
                   SET status = 'RESOLVED', next_retry_at = NULL, resolved_at = ?
                 WHERE stage = ?
                   AND delivery_target_id = ?
                   AND candidate_id IS NOT DISTINCT FROM ?
                   AND status <> 'RESOLVED'
                """,
                Timestamp.from(resolvedAt),
                stage,
                targetId,
                candidateId
        );
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record FailureState(UUID failureId, String status, int attemptCount) {
    }
}
