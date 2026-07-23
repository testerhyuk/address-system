package com.address.address_system.address.coordinate.quality;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.coordinate.quality.CoordinateQualityPolicy.Evaluation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoordinateQualityRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoordinateQualityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UUID> findGeneratedCandidateIds(int limit) {
        return jdbcTemplate.query(
                """
                SELECT candidate.candidate_id
                  FROM address.delivery_coordinate_candidate candidate
                  JOIN address.coordinate_analysis_run run
                    ON run.run_id = candidate.run_id
                  JOIN address.delivery_target target
                    ON target.delivery_target_id = candidate.delivery_target_id
                  JOIN address.road_address road
                    ON road.road_address_id = target.road_address_id
                 WHERE candidate.status = 'GENERATED'
                   AND run.status = 'COMPLETED'
                   AND target.status = 'ACTIVE'
                   AND road.status = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM address.coordinate_processing_failure failure
                        WHERE failure.stage = 'QUALITY'
                          AND failure.candidate_id = candidate.candidate_id
                          AND (
                              failure.status = 'EXHAUSTED'
                              OR (
                                  failure.status = 'RETRY_SCHEDULED'
                                  AND failure.next_retry_at > CURRENT_TIMESTAMP
                              )
                          )
                   )
              ORDER BY candidate.created_at, candidate.candidate_id
                 LIMIT ?
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("candidate_id", UUID.class),
                limit
        );
    }

    public Optional<CandidateEvidence> lockGeneratedCandidate(UUID candidateId) {
        return jdbcTemplate.query(
                """
                SELECT candidate.candidate_id,
                       candidate.run_id,
                       candidate.delivery_target_id,
                       candidate.sample_count AS candidate_sample_count,
                       candidate.radius_meters,
                       run.sample_count AS total_sample_count,
                       run.outlier_count,
                       candidate.candidate_id = (
                           SELECT ranked.candidate_id
                             FROM address.delivery_coordinate_candidate ranked
                            WHERE ranked.run_id = candidate.run_id
                         ORDER BY ranked.sample_count DESC,
                                  ranked.radius_meters,
                                  ranked.candidate_id
                            LIMIT 1
                       ) AS top_candidate,
                       COALESCE((
                           SELECT ranked.sample_count
                             FROM address.delivery_coordinate_candidate ranked
                            WHERE ranked.run_id = candidate.run_id
                         ORDER BY ranked.sample_count DESC,
                                  ranked.radius_meters,
                                  ranked.candidate_id
                           OFFSET 1 LIMIT 1
                       ), 0) AS second_sample_count
                  FROM address.delivery_coordinate_candidate candidate
                  JOIN address.coordinate_analysis_run run
                    ON run.run_id = candidate.run_id
                 WHERE candidate.candidate_id = ?
                   AND candidate.status = 'GENERATED'
                   AND run.status = 'COMPLETED'
                   FOR UPDATE OF candidate
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new CandidateEvidence(
                                resultSet.getObject("candidate_id", UUID.class),
                                resultSet.getObject("run_id", UUID.class),
                                resultSet.getObject("delivery_target_id", UUID.class),
                                resultSet.getInt("candidate_sample_count"),
                                resultSet.getInt("total_sample_count"),
                                resultSet.getInt("outlier_count"),
                                resultSet.getBigDecimal("radius_meters"),
                                resultSet.getBoolean("top_candidate"),
                                resultSet.getInt("second_sample_count")
                        ))
                        : Optional.empty(),
                candidateId
        );
    }

    public Optional<TargetState> lockActiveTarget(UUID targetId) {
        return jdbcTemplate.query(
                """
                SELECT target.coordinate_serving_status
                  FROM address.delivery_target target
                  JOIN address.road_address road
                    ON road.road_address_id = target.road_address_id
                   AND road.status = 'ACTIVE'
                 WHERE target.delivery_target_id = ?
                   AND target.status = 'ACTIVE'
                 FOR UPDATE OF target
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new TargetState(
                                "ENABLED".equals(resultSet.getString(
                                        "coordinate_serving_status"
                                ))
                        ))
                        : Optional.empty(),
                targetId
        );
    }

    public Optional<ActiveCoordinate> findActiveCoordinate(
            UUID targetId,
            UUID candidateId
    ) {
        return jdbcTemplate.query(
                """
                SELECT active.coordinate_id,
                       active.version_no,
                       active.quality_score,
                       active.sample_count,
                       CAST(ST_Distance(active.location, candidate.location) AS NUMERIC)
                           AS distance_meters
                  FROM address.delivery_coordinate_version active
                  JOIN address.delivery_coordinate_candidate candidate
                    ON candidate.candidate_id = ?
                 WHERE active.delivery_target_id = ?
                   AND active.status = 'ACTIVE'
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new ActiveCoordinate(
                                resultSet.getObject("coordinate_id", UUID.class),
                                resultSet.getLong("version_no"),
                                resultSet.getBigDecimal("quality_score"),
                                resultSet.getInt("sample_count"),
                                resultSet.getBigDecimal("distance_meters")
                        ))
                        : Optional.empty(),
                candidateId,
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

    public void createActiveCoordinate(
            UUID coordinateId,
            CandidateEvidence evidence,
            long versionNumber,
            BigDecimal qualityScore,
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
                qualityScore,
                Timestamp.from(activatedAt),
                evidence.candidateId()
        );
    }

    public void confirmActiveCoordinate(
            UUID coordinateId,
            BigDecimal qualityScore,
            int accumulatedSampleCount
    ) {
        jdbcTemplate.update(
                """
                UPDATE address.delivery_coordinate_version
                   SET quality_score = ?, sample_count = ?
                 WHERE coordinate_id = ?
                   AND status = 'ACTIVE'
                """,
                qualityScore,
                accumulatedSampleCount,
                coordinateId
        );
    }

    public void updateCandidateStatus(UUID candidateId, String status) {
        jdbcTemplate.update(
                """
                UPDATE address.delivery_coordinate_candidate
                   SET status = ?
                 WHERE candidate_id = ?
                   AND status = 'GENERATED'
                """,
                status,
                candidateId
        );
    }

    public void recordEvaluation(
            UUID evaluationId,
            CandidateEvidence evidence,
            Evaluation evaluation,
            UUID promotedCoordinateId,
            BigDecimal distanceFromActiveMeters,
            CoordinateQualityProperties properties,
            Instant evaluatedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO address.delivery_coordinate_quality_evaluation (
                    evaluation_id, candidate_id, run_id, delivery_target_id,
                    promoted_coordinate_id, decision, reason_code, policy_version,
                    quality_score, dominance_ratio, outlier_ratio,
                    distance_from_active_meters, min_candidate_samples,
                    max_radius_meters, max_outlier_ratio, min_dominant_ratio,
                    min_dominance_gap, min_promotion_score,
                    max_automatic_shift_meters, evaluated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                evaluationId,
                evidence.candidateId(),
                evidence.runId(),
                evidence.targetId(),
                promotedCoordinateId,
                evaluation.decision().name(),
                evaluation.reason().name(),
                properties.policyVersion(),
                evaluation.qualityScore(),
                evaluation.dominanceRatio(),
                evaluation.outlierRatio(),
                distanceFromActiveMeters,
                properties.minCandidateSamples(),
                properties.maxRadiusMeters(),
                properties.maxOutlierRatio(),
                properties.minDominantRatio(),
                properties.minDominanceGap(),
                properties.minPromotionScore(),
                properties.maxAutomaticShiftMeters(),
                Timestamp.from(evaluatedAt)
        );
    }

    public record CandidateEvidence(
            UUID candidateId,
            UUID runId,
            UUID targetId,
            int candidateSampleCount,
            int totalSampleCount,
            int outlierCount,
            BigDecimal radiusMeters,
            boolean topCandidate,
            int secondSampleCount
    ) {
    }

    public record ActiveCoordinate(
            UUID coordinateId,
            long versionNumber,
            BigDecimal qualityScore,
            int sampleCount,
            BigDecimal distanceMeters
    ) {
    }

    public record TargetState(boolean automaticPromotionAllowed) {
    }
}
