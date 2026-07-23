package com.address.address_system.address.coordinate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.address.address_system.address.coordinate.admin.CoordinateAdminService;
import com.address.address_system.address.coordinate.analysis.CoordinateAnalysisService;
import com.address.address_system.address.coordinate.application.DeliveryCoordinateLookupService;
import com.address.address_system.address.coordinate.application.DeliveryCoordinateService;
import com.address.address_system.address.coordinate.application.DeliveryCoordinateService.Command;
import com.address.address_system.address.coordinate.model.DeliveryCoordinateLookupResult.CoordinateSource;
import com.address.address_system.address.coordinate.persistence.DeliveryCoordinateRepository;
import com.address.address_system.address.coordinate.quality.CoordinateQualityService;
import com.address.address_system.address.target.application.DeliveryTargetService;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfEnvironmentVariable(
        named = "ADDRESS_INTEGRATION_TEST",
        matches = "true"
)
class CoordinateWorkflowIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryCoordinateService coordinateService;

    @Autowired
    private CoordinateAnalysisService analysisService;

    @Autowired
    private CoordinateQualityService qualityService;

    @Autowired
    private DeliveryCoordinateLookupService lookupService;

    @Autowired
    private CoordinateAdminService adminService;

    @Autowired
    private DeliveryTargetService targetService;

    @Autowired
    private DeliveryCoordinateRepository coordinateRepository;

    @Autowired
    private Clock clock;

    @Test
    void completesReceiveAnalysisPromotionRecoveryAndRetentionFlow() {
        UUID roadAddressId = jdbcTemplate.queryForObject(
                """
                SELECT road_address_id
                  FROM address.road_address
                 WHERE status = 'ACTIVE'
                   AND apartment_status IN ('APARTMENT', 'UNKNOWN')
              ORDER BY road_address_id
                 LIMIT 1
                """,
                UUID.class
        );
        UUID targetId = UUID.randomUUID();
        String dong = "통합" + targetId.toString().substring(0, 6) + "동";
        jdbcTemplate.update(
                """
                INSERT INTO address.delivery_target (
                    delivery_target_id, road_address_id, target_type,
                    building_dong_name, normalized_building_dong, source_type
                ) VALUES (?, ?, 'BUILDING_DONG', ?, ?, 'EXTERNAL_DELIVERY')
                """,
                targetId,
                roadAddressId,
                dong,
                dong
        );

        Instant completedAt = clock.instant().minusSeconds(60);
        List<Point> points = List.of(
                new Point("37.56650", "126.97800"),
                new Point("37.56651", "126.97805"),
                new Point("37.56649", "126.97810"),
                new Point("37.56652", "126.97795"),
                new Point("37.56647", "126.97802"),
                new Point("37.56654", "126.97807"),
                new Point("37.56645", "126.97798"),
                new Point("37.56648", "126.97804"),
                new Point("37.56653", "126.97801"),
                new Point("37.58000", "126.99000")
        );
        receive(targetId, completedAt, points);

        assertThat(analysisService.analyze(targetId)).isTrue();
        UUID candidateId = latestGeneratedCandidate(targetId);
        assertThat(qualityService.evaluate(candidateId)).isTrue();

        var verified = lookupService.find(targetId);
        assertThat(verified.source()).isEqualTo(CoordinateSource.VERIFIED);
        assertThat(verified.versionNo()).isEqualTo(1L);

        receive(targetId, completedAt.plusSeconds(60), points);
        assertThat(analysisService.analyze(targetId)).isTrue();
        assertThat(qualityService.evaluate(
                latestGeneratedCandidate(targetId)
        )).isTrue();
        assertThat(activeSampleCount(targetId)).isEqualTo(18);

        adminService.excludeActiveCoordinate(
                targetId,
                "integration-operator",
                "통합 테스트 긴급 좌표 제외"
        );
        assertThat(coordinateServingStatus(targetId)).isEqualTo("SUSPENDED");
        assertThat(targetService.resolve(roadAddressId, dong).deliveryTargetId())
                .isEqualTo(targetId);
        assertThat(coordinateServingStatus(targetId)).isEqualTo("SUSPENDED");

        var manual = adminService.activateManualCoordinate(
                targetId,
                new BigDecimal("37.5666000"),
                new BigDecimal("126.9781000"),
                "integration-operator",
                "통합 테스트 수동 보정"
        );
        assertThat(manual.versionNumber()).isEqualTo(2L);
        assertThat(coordinateServingStatus(targetId)).isEqualTo("ENABLED");

        var restored = adminService.restoreCoordinate(
                targetId,
                1L,
                "integration-operator",
                "통합 테스트 이전 좌표 복구"
        );
        assertThat(restored.versionNumber()).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT source_type
                  FROM address.delivery_coordinate_version
                 WHERE delivery_target_id = ? AND status = 'ACTIVE'
                """,
                String.class,
                targetId
        )).isEqualTo("RESTORED");

        var reanalysis = adminService.requestReanalysis(
                targetId,
                "integration-operator",
                "통합 테스트 재분석"
        );
        assertThat(reanalysis.affectedSampleCount()).isEqualTo(20);
        assertThat(analysisService.analyze(targetId)).isTrue();
        assertThat(qualityService.evaluate(
                latestGeneratedCandidate(targetId)
        )).isTrue();
        assertThat(activeSampleCount(targetId)).isEqualTo(18);

        jdbcTemplate.update(
                """
                UPDATE coordinate_raw.delivery_coordinate_sample
                   SET received_at = CURRENT_TIMESTAMP - INTERVAL '29 days',
                       expires_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                 WHERE sample_id = (
                     SELECT sample_id
                       FROM coordinate_raw.delivery_coordinate_sample
                      WHERE delivery_target_id = ?
                      LIMIT 1
                 )
                """,
                targetId
        );
        coordinateRepository.deleteExpired();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM coordinate_raw.delivery_coordinate_sample
                 WHERE delivery_target_id = ?
                """,
                Integer.class,
                targetId
        )).isEqualTo(19);

        UUID retirementBatchId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO address.address_import_batch (
                    batch_id, source_type, source_file_name, source_file_sha256,
                    source_reference_date, file_size_bytes, status, import_mode,
                    total_row_count, accepted_row_count, rejected_row_count,
                    started_at, completed_at
                ) VALUES (
                    ?, 'ROAD', 'integration-retirement.csv', ?, CURRENT_DATE,
                    0, 'COMPLETED', 'DELTA', 0, 0, 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """,
                retirementBatchId,
                retirementBatchId.toString().replace("-", "")
                        + retirementBatchId.toString().replace("-", "")
        );
        jdbcTemplate.update(
                """
                UPDATE address.road_address
                   SET status = 'RETIRED',
                       effective_date = CURRENT_DATE,
                       last_movement_reason_code = '63',
                       version_no = version_no + 1,
                       source_batch_id = ?,
                       source_row_number = 2,
                       updated_at = CURRENT_TIMESTAMP,
                       retired_at = CURRENT_TIMESTAMP
                 WHERE road_address_id = ?
                """,
                retirementBatchId,
                roadAddressId
        );

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT status
                  FROM address.delivery_target
                 WHERE delivery_target_id = ?
                """,
                String.class,
                targetId
        )).isEqualTo("INACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM coordinate_raw.delivery_coordinate_sample
                 WHERE delivery_target_id = ?
                """,
                Integer.class,
                targetId
        )).isZero();
    }

    private void receive(
            UUID targetId,
            Instant completedAt,
            List<Point> points
    ) {
        points.forEach(point -> coordinateService.receive(new Command(
                UUID.randomUUID(),
                targetId,
                new BigDecimal(point.latitude()),
                new BigDecimal(point.longitude()),
                new BigDecimal("10"),
                completedAt
        )));
    }

    private UUID latestGeneratedCandidate(UUID targetId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT candidate_id
                  FROM address.delivery_coordinate_candidate
                 WHERE delivery_target_id = ?
                   AND status = 'GENERATED'
              ORDER BY created_at DESC, candidate_id
                 LIMIT 1
                """,
                UUID.class,
                targetId
        );
    }

    private int activeSampleCount(UUID targetId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT sample_count
                  FROM address.delivery_coordinate_version
                 WHERE delivery_target_id = ?
                   AND status = 'ACTIVE'
                """,
                Integer.class,
                targetId
        );
    }

    private String coordinateServingStatus(UUID targetId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT coordinate_serving_status
                  FROM address.delivery_target
                 WHERE delivery_target_id = ?
                """,
                String.class,
                targetId
        );
    }

    private record Point(String latitude, String longitude) {
    }
}
