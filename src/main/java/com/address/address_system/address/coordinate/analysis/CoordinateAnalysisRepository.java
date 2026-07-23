package com.address.address_system.address.coordinate.analysis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoordinateAnalysisRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoordinateAnalysisRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<UUID> findReadyTargets(Instant sampleFrom, int minSamples, int limit) {
        return jdbcTemplate.query(
                """
                SELECT sample.delivery_target_id
                  FROM coordinate_raw.delivery_coordinate_sample sample
                  JOIN address.delivery_target target
                    ON target.delivery_target_id = sample.delivery_target_id
                   AND target.status = 'ACTIVE'
                  JOIN address.road_address road
                    ON road.road_address_id = target.road_address_id
                   AND road.status = 'ACTIVE'
                 WHERE sample.processing_status = 'PENDING'
                   AND sample.completed_at >= ?
                   AND sample.expires_at > CURRENT_TIMESTAMP
                   AND NOT EXISTS (
                       SELECT 1
                         FROM address.coordinate_processing_failure failure
                        WHERE failure.stage = 'ANALYSIS'
                          AND failure.delivery_target_id = sample.delivery_target_id
                          AND (
                              failure.status = 'EXHAUSTED'
                              OR (
                                  failure.status = 'RETRY_SCHEDULED'
                                  AND failure.next_retry_at > CURRENT_TIMESTAMP
                              )
                          )
                   )
              GROUP BY sample.delivery_target_id
                HAVING count(*) >= ?
              ORDER BY min(sample.completed_at)
                 LIMIT ?
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("delivery_target_id", UUID.class),
                Timestamp.from(sampleFrom),
                minSamples,
                limit
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

    public int countPendingSamples(UUID targetId, Instant sampleFrom) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM coordinate_raw.delivery_coordinate_sample
                 WHERE delivery_target_id = ?
                   AND processing_status = 'PENDING'
                   AND completed_at >= ?
                   AND expires_at > CURRENT_TIMESTAMP
                """,
                Integer.class,
                targetId,
                Timestamp.from(sampleFrom)
        );
        return count == null ? 0 : count;
    }

    public void createRun(
            UUID runId,
            UUID targetId,
            CoordinateAnalysisProperties properties,
            Instant startedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO address.coordinate_analysis_run (
                    run_id, delivery_target_id, status, eps_meters,
                    min_points, sample_count, started_at
                ) VALUES (?, ?, 'RUNNING', ?, ?, 0, ?)
                """,
                runId,
                targetId,
                properties.epsMeters(),
                properties.minPoints(),
                Timestamp.from(startedAt)
        );
    }

    public void clusterSamples(
            UUID runId,
            UUID targetId,
            Instant sampleFrom,
            CoordinateAnalysisProperties properties
    ) {
        jdbcTemplate.update(
                """
                WITH samples AS (
                    SELECT sample_id,
                           ST_Transform(location::geometry, 5179) AS projected
                      FROM coordinate_raw.delivery_coordinate_sample
                     WHERE delivery_target_id = ?
                       AND processing_status IN ('PENDING', 'PROCESSED')
                       AND completed_at >= ?
                       AND expires_at > CURRENT_TIMESTAMP
                ), clustered AS (
                    SELECT sample_id,
                           ST_ClusterDBSCAN(projected, ?, ?) OVER (
                               ORDER BY sample_id
                           ) AS cluster_no
                      FROM samples
                )
                INSERT INTO coordinate_raw.delivery_coordinate_analysis_assignment (
                    run_id, sample_id, cluster_no, is_outlier
                )
                SELECT ?, sample_id, cluster_no, cluster_no IS NULL
                  FROM clustered
                """,
                targetId,
                Timestamp.from(sampleFrom),
                properties.epsMeters(),
                properties.minPoints(),
                runId
        );
    }

    public void createCandidates(UUID runId, UUID targetId) {
        jdbcTemplate.update(
                """
                WITH points AS (
                    SELECT assignment.cluster_no,
                           ST_Transform(sample.location::geometry, 5179) AS projected
                      FROM coordinate_raw.delivery_coordinate_analysis_assignment assignment
                      JOIN coordinate_raw.delivery_coordinate_sample sample
                        ON sample.sample_id = assignment.sample_id
                     WHERE assignment.run_id = ?
                       AND assignment.cluster_no IS NOT NULL
                ), centers AS (
                    SELECT cluster_no,
                           count(*)::INTEGER AS sample_count,
                           ST_Centroid(ST_Collect(projected)) AS center
                      FROM points
                  GROUP BY cluster_no
                ), stats AS (
                    SELECT centers.cluster_no,
                           centers.sample_count,
                           centers.center,
                           max(ST_Distance(points.projected, centers.center)) AS radius
                      FROM centers
                      JOIN points USING (cluster_no)
                  GROUP BY centers.cluster_no, centers.sample_count, centers.center
                )
                INSERT INTO address.delivery_coordinate_candidate (
                    candidate_id, run_id, delivery_target_id, cluster_no,
                    location, sample_count, radius_meters
                )
                SELECT gen_random_uuid(), ?, ?, cluster_no,
                       ST_Transform(center, 4326)::geography,
                       sample_count, radius
                  FROM stats
                """,
                runId,
                runId,
                targetId
        );
    }

    public AnalysisStats readStats(UUID runId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)::INTEGER AS sample_count,
                       count(DISTINCT cluster_no)::INTEGER AS cluster_count,
                       count(*) FILTER (WHERE is_outlier)::INTEGER AS outlier_count
                  FROM coordinate_raw.delivery_coordinate_analysis_assignment
                 WHERE run_id = ?
                """,
                (resultSet, rowNumber) -> new AnalysisStats(
                        resultSet.getInt("sample_count"),
                        resultSet.getInt("cluster_count"),
                        resultSet.getInt("outlier_count")
                ),
                runId
        );
    }

    public void complete(UUID runId, AnalysisStats stats, Instant completedAt) {
        jdbcTemplate.update(
                """
                UPDATE address.coordinate_analysis_run
                   SET status = 'COMPLETED', sample_count = ?, cluster_count = ?,
                       outlier_count = ?, completed_at = ?
                 WHERE run_id = ?
                """,
                stats.sampleCount(), stats.clusterCount(), stats.outlierCount(),
                Timestamp.from(completedAt), runId
        );
        jdbcTemplate.update(
                """
                UPDATE coordinate_raw.delivery_coordinate_sample sample
                   SET processing_status = 'PROCESSED', processed_at = ?
                  FROM coordinate_raw.delivery_coordinate_analysis_assignment assignment
                 WHERE assignment.run_id = ?
                   AND assignment.sample_id = sample.sample_id
                   AND sample.processing_status = 'PENDING'
                """,
                Timestamp.from(completedAt), runId
        );
    }

    public record AnalysisStats(int sampleCount, int clusterCount, int outlierCount) {
    }
}
