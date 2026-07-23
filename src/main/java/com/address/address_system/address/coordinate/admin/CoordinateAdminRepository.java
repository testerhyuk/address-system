package com.address.address_system.address.coordinate.admin;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoordinateAdminRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoordinateAdminRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CandidateView> findCandidates(String status, int limit) {
        return jdbcTemplate.query(
                """
                SELECT candidate.candidate_id,
                       candidate.delivery_target_id,
                       candidate.run_id,
                       ST_Y(candidate.location::geometry) AS latitude,
                       ST_X(candidate.location::geometry) AS longitude,
                       candidate.sample_count,
                       candidate.radius_meters,
                       candidate.status,
                       evaluation.quality_score,
                       evaluation.dominance_ratio,
                       evaluation.outlier_ratio,
                       evaluation.reason_code,
                       evaluation.evaluated_at,
                       active.version_no AS active_version_no
                  FROM address.delivery_coordinate_candidate candidate
             LEFT JOIN address.delivery_coordinate_quality_evaluation evaluation
                    ON evaluation.candidate_id = candidate.candidate_id
             LEFT JOIN address.delivery_coordinate_version active
                    ON active.delivery_target_id = candidate.delivery_target_id
                   AND active.status = 'ACTIVE'
                 WHERE candidate.status = ?
              ORDER BY candidate.created_at, candidate.candidate_id
                 LIMIT ?
                """,
                (resultSet, rowNumber) -> new CandidateView(
                        resultSet.getObject("candidate_id", UUID.class),
                        resultSet.getObject("delivery_target_id", UUID.class),
                        resultSet.getObject("run_id", UUID.class),
                        resultSet.getBigDecimal("latitude"),
                        resultSet.getBigDecimal("longitude"),
                        resultSet.getInt("sample_count"),
                        resultSet.getBigDecimal("radius_meters"),
                        resultSet.getString("status"),
                        resultSet.getBigDecimal("quality_score"),
                        resultSet.getBigDecimal("dominance_ratio"),
                        resultSet.getBigDecimal("outlier_ratio"),
                        resultSet.getString("reason_code"),
                        timestampToInstant(resultSet.getTimestamp("evaluated_at")),
                        resultSet.getObject("active_version_no", Long.class)
                ),
                status,
                limit
        );
    }

    public List<ProcessingFailureView> findProcessingFailures(
            String status,
            int limit
    ) {
        return jdbcTemplate.query(
                """
                SELECT failure_id, stage, delivery_target_id, candidate_id,
                       status, attempt_count, failure_code, first_failed_at,
                       last_failed_at, next_retry_at
                  FROM address.coordinate_processing_failure
                 WHERE status = ?
              ORDER BY last_failed_at, failure_id
                 LIMIT ?
                """,
                (resultSet, rowNumber) -> new ProcessingFailureView(
                        resultSet.getObject("failure_id", UUID.class),
                        resultSet.getString("stage"),
                        resultSet.getObject("delivery_target_id", UUID.class),
                        resultSet.getObject("candidate_id", UUID.class),
                        resultSet.getString("status"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getString("failure_code"),
                        timestampToInstant(resultSet.getTimestamp("first_failed_at")),
                        timestampToInstant(resultSet.getTimestamp("last_failed_at")),
                        timestampToInstant(resultSet.getTimestamp("next_retry_at"))
                ),
                status,
                limit
        );
    }

    public Optional<ReviewCandidate> lockReviewCandidate(UUID candidateId) {
        return jdbcTemplate.query(
                """
                SELECT candidate.candidate_id,
                       candidate.delivery_target_id,
                       candidate.run_id,
                       candidate.sample_count,
                       evaluation.quality_score
                  FROM address.delivery_coordinate_candidate candidate
                  JOIN address.delivery_coordinate_quality_evaluation evaluation
                    ON evaluation.candidate_id = candidate.candidate_id
                 WHERE candidate.candidate_id = ?
                   AND candidate.status = 'REVIEW_REQUIRED'
                   FOR UPDATE OF candidate
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new ReviewCandidate(
                                resultSet.getObject("candidate_id", UUID.class),
                                resultSet.getObject("delivery_target_id", UUID.class),
                                resultSet.getObject("run_id", UUID.class),
                                resultSet.getInt("sample_count"),
                                resultSet.getBigDecimal("quality_score")
                        ))
                        : Optional.empty(),
                candidateId
        );
    }

    public boolean lockActiveTarget(UUID targetId) {
        return !jdbcTemplate.query(
                """
                SELECT target.delivery_target_id
                  FROM address.delivery_target target
                  JOIN address.road_address road
                    ON road.road_address_id = target.road_address_id
                   AND road.status = 'ACTIVE'
                 WHERE target.delivery_target_id = ?
                   AND target.status = 'ACTIVE'
                 FOR UPDATE OF target
                """,
                (resultSet, rowNumber) -> resultSet.getObject(1, UUID.class),
                targetId
        ).isEmpty();
    }

    public Optional<ActiveCoordinateVersion> findActiveCoordinateVersion(
            UUID targetId
    ) {
        return jdbcTemplate.query(
                """
                SELECT coordinate_id, version_no
                  FROM address.delivery_coordinate_version
                 WHERE delivery_target_id = ?
                   AND status = 'ACTIVE'
                 FOR UPDATE
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new ActiveCoordinateVersion(
                                resultSet.getObject("coordinate_id", UUID.class),
                                resultSet.getLong("version_no")
                        ))
                        : Optional.empty(),
                targetId
        );
    }

    public void excludeActiveCoordinate(UUID targetId, Instant excludedAt) {
        jdbcTemplate.update(
                """
                UPDATE address.delivery_coordinate_version
                   SET status = 'EXCLUDED', retired_at = ?
                 WHERE delivery_target_id = ?
                   AND status = 'ACTIVE'
                """,
                Timestamp.from(excludedAt),
                targetId
        );
    }

    public void suspendCoordinateServing(UUID targetId) {
        jdbcTemplate.update(
                """
                UPDATE address.delivery_target
                   SET coordinate_serving_status = 'SUSPENDED',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE delivery_target_id = ?
                """,
                targetId
        );
    }

    public void enableCoordinateServing(UUID targetId) {
        jdbcTemplate.update(
                """
                UPDATE address.delivery_target
                   SET coordinate_serving_status = 'ENABLED',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE delivery_target_id = ?
                """,
                targetId
        );
    }

    public long nextVersionNumber(UUID targetId) {
        Long version = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(max(version_no), 0) + 1
                  FROM address.delivery_coordinate_version
                 WHERE delivery_target_id = ?
                """,
                Long.class,
                targetId
        );
        return version == null ? 1 : version;
    }

    public void retireActiveCoordinate(UUID targetId, Instant retiredAt) {
        jdbcTemplate.update(
                """
                UPDATE address.delivery_coordinate_version
                   SET status = 'SUPERSEDED', retired_at = ?
                 WHERE delivery_target_id = ?
                   AND status = 'ACTIVE'
                """,
                Timestamp.from(retiredAt),
                targetId
        );
    }

    public void activateCandidate(
            UUID coordinateId,
            ReviewCandidate candidate,
            long versionNumber,
            Instant activatedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO address.delivery_coordinate_version (
                    coordinate_id, delivery_target_id, version_no, location,
                    source_type, status, quality_score, sample_count,
                    activated_at, source_candidate_id
                )
                SELECT ?, candidate.delivery_target_id, ?, candidate.location,
                       'ANALYSIS', 'ACTIVE', ?, candidate.sample_count,
                       ?, candidate.candidate_id
                  FROM address.delivery_coordinate_candidate candidate
                 WHERE candidate.candidate_id = ?
                """,
                coordinateId,
                versionNumber,
                candidate.qualityScore(),
                Timestamp.from(activatedAt),
                candidate.candidateId()
        );
        jdbcTemplate.update(
                """
                UPDATE address.delivery_coordinate_candidate
                   SET status = 'PROMOTED'
                 WHERE candidate_id = ?
                   AND status = 'REVIEW_REQUIRED'
                """,
                candidate.candidateId()
        );
    }

    public void rejectCandidate(UUID candidateId) {
        jdbcTemplate.update(
                """
                UPDATE address.delivery_coordinate_candidate
                   SET status = 'REJECTED'
                 WHERE candidate_id = ?
                   AND status = 'REVIEW_REQUIRED'
                """,
                candidateId
        );
    }

    public void createManualCoordinate(
            UUID coordinateId,
            UUID targetId,
            long versionNumber,
            BigDecimal latitude,
            BigDecimal longitude,
            Instant activatedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO address.delivery_coordinate_version (
                    coordinate_id, delivery_target_id, version_no, location,
                    source_type, status, sample_count, activated_at
                ) VALUES (
                    ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    'MANUAL', 'ACTIVE', 0, ?
                )
                """,
                coordinateId,
                targetId,
                versionNumber,
                longitude,
                latitude,
                Timestamp.from(activatedAt)
        );
    }

    public Optional<CoordinateVersion> findVersion(
            UUID targetId,
            long versionNumber
    ) {
        return jdbcTemplate.query(
                """
                SELECT coordinate_id, version_no, status,
                       quality_score, sample_count
                  FROM address.delivery_coordinate_version
                 WHERE delivery_target_id = ?
                   AND version_no = ?
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new CoordinateVersion(
                                resultSet.getObject("coordinate_id", UUID.class),
                                resultSet.getLong("version_no"),
                                resultSet.getString("status"),
                                resultSet.getBigDecimal("quality_score"),
                                resultSet.getInt("sample_count")
                        ))
                        : Optional.empty(),
                targetId,
                versionNumber
        );
    }

    public void restoreCoordinate(
            UUID coordinateId,
            UUID targetId,
            long versionNumber,
            UUID sourceCoordinateId,
            Instant activatedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO address.delivery_coordinate_version (
                    coordinate_id, delivery_target_id, version_no, location,
                    source_type, status, quality_score, sample_count, activated_at
                )
                SELECT ?, ?, ?, source.location,
                       'RESTORED', 'ACTIVE', source.quality_score,
                       source.sample_count, ?
                  FROM address.delivery_coordinate_version source
                 WHERE source.coordinate_id = ?
                """,
                coordinateId,
                targetId,
                versionNumber,
                Timestamp.from(activatedAt),
                sourceCoordinateId
        );
    }

    public int requeueRetainedSamples(UUID targetId) {
        return jdbcTemplate.update(
                """
                UPDATE coordinate_raw.delivery_coordinate_sample
                   SET processing_status = 'PENDING',
                       failure_code = NULL,
                       processed_at = NULL
                 WHERE delivery_target_id = ?
                   AND processing_status IN ('PROCESSED', 'FAILED')
                   AND expires_at > CURRENT_TIMESTAMP
                """,
                targetId
        );
    }

    public int retryFailedSamples(UUID targetId) {
        return jdbcTemplate.update(
                """
                UPDATE coordinate_raw.delivery_coordinate_sample
                   SET processing_status = 'PENDING',
                       failure_code = NULL,
                       retry_count = retry_count + 1,
                       processed_at = NULL
                 WHERE delivery_target_id = ?
                   AND processing_status = 'FAILED'
                   AND expires_at > CURRENT_TIMESTAMP
                """,
                targetId
        );
    }

    public void resolveProcessingFailures(UUID targetId, Instant resolvedAt) {
        jdbcTemplate.update(
                """
                UPDATE address.coordinate_processing_failure
                   SET status = 'RESOLVED', next_retry_at = NULL, resolved_at = ?
                 WHERE delivery_target_id = ?
                   AND status <> 'RESOLVED'
                """,
                Timestamp.from(resolvedAt),
                targetId
        );
    }

    public void recordAudit(
            UUID auditId,
            String actionType,
            String actorClientId,
            String reason,
            UUID targetId,
            UUID candidateId,
            UUID coordinateId,
            UUID sourceCoordinateId,
            Integer affectedSampleCount,
            Instant occurredAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO address.coordinate_operation_audit (
                    audit_id, action_type, actor_client_id, reason,
                    delivery_target_id, candidate_id, coordinate_id,
                    source_coordinate_id, affected_sample_count, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                auditId,
                actionType,
                actorClientId,
                reason,
                targetId,
                candidateId,
                coordinateId,
                sourceCoordinateId,
                affectedSampleCount,
                Timestamp.from(occurredAt)
        );
    }

    private static Instant timestampToInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record CandidateView(
            UUID candidateId,
            UUID targetId,
            UUID runId,
            BigDecimal latitude,
            BigDecimal longitude,
            int sampleCount,
            BigDecimal radiusMeters,
            String status,
            BigDecimal qualityScore,
            BigDecimal dominanceRatio,
            BigDecimal outlierRatio,
            String reasonCode,
            Instant evaluatedAt,
            Long activeVersionNumber
    ) {
    }

    public record ReviewCandidate(
            UUID candidateId,
            UUID targetId,
            UUID runId,
            int sampleCount,
            BigDecimal qualityScore
    ) {
    }

    public record CoordinateVersion(
            UUID coordinateId,
            long versionNumber,
            String status,
            BigDecimal qualityScore,
            int sampleCount
    ) {
    }

    public record ActiveCoordinateVersion(UUID coordinateId, long versionNumber) {
    }

    public record ProcessingFailureView(
            UUID failureId,
            String stage,
            UUID targetId,
            UUID candidateId,
            String status,
            int attemptCount,
            String failureCode,
            Instant firstFailedAt,
            Instant lastFailedAt,
            Instant nextRetryAt
    ) {
    }
}
