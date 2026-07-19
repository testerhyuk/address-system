package com.address.address_system.address.importer.jibun.apply;

import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

public class JibunAddressApplyRepository {

    private static final long JIBUN_ADDRESS_APPLY_LOCK_KEY = 8_123_476_102_948_113L;

    private static final String SAME_SOURCE_KEY = """
            current_address.mgmt_num = btrim(staging.mgmt_num)
            AND current_address.b_dong_name = btrim(staging.b_dong_name)
            AND current_address.ri_name IS NOT DISTINCT FROM NULLIF(btrim(staging.ri_name), '')
            AND current_address.jibun_main = btrim(staging.jibun_main)::integer
            AND current_address.jibun_sub = btrim(staging.jibun_sub)::integer
            """;

    private static final String INSERT_NEW_ADDRESSES = """
            INSERT INTO address.jibun_address (
                road_address_id,
                mgmt_num,
                legal_area_code,
                b_dong_name,
                ri_name,
                jibun_main,
                jibun_sub,
                status,
                version_no,
                source_batch_id,
                source_row_number,
                source_reference_date
            )
            SELECT road.road_address_id,
                   btrim(staging.mgmt_num),
                   substring(btrim(staging.mgmt_num) FROM 1 FOR 8),
                   btrim(staging.b_dong_name),
                   NULLIF(btrim(staging.ri_name), ''),
                   btrim(staging.jibun_main)::integer,
                   btrim(staging.jibun_sub)::integer,
                   'ACTIVE',
                   1,
                   staging.batch_id,
                   staging.source_row_number,
                   ?
            FROM address.address_jibun_staging staging
            JOIN address.road_address road
              ON road.mgmt_num = btrim(staging.mgmt_num)
             AND road.status = 'ACTIVE'
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND NOT EXISTS (
                  SELECT 1
                  FROM address.jibun_address current_address
                  WHERE
            """ + SAME_SOURCE_KEY + """
              )
            """;

    private static final String REACTIVATE_ADDRESSES = """
            UPDATE address.jibun_address current_address
            SET road_address_id = road.road_address_id,
                status = 'ACTIVE',
                version_no = current_address.version_no + 1,
                source_batch_id = staging.batch_id,
                source_row_number = staging.source_row_number,
                source_reference_date = ?,
                updated_at = CURRENT_TIMESTAMP,
                retired_at = NULL
            FROM address.address_jibun_staging staging
            JOIN address.road_address road
              ON road.mgmt_num = btrim(staging.mgmt_num)
             AND road.status = 'ACTIVE'
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND current_address.status = 'RETIRED'
              AND
            """ + SAME_SOURCE_KEY;

    private static final String MARK_STAGING_APPLIED = """
            UPDATE address.address_jibun_staging staging
            SET processing_status = 'APPLIED',
                apply_action = CASE
                    WHEN current_address.source_batch_id = staging.batch_id
                     AND current_address.source_row_number = staging.source_row_number
                    THEN CASE
                        WHEN current_address.version_no = 1 THEN 'CREATED'
                        ELSE 'REACTIVATED'
                    END
                    ELSE 'NO_CHANGE'
                END,
                applied_at = CURRENT_TIMESTAMP
            FROM address.jibun_address current_address
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND current_address.status = 'ACTIVE'
              AND
            """ + SAME_SOURCE_KEY;

    private static final String RETIRE_MISSING_ADDRESSES = """
            UPDATE address.jibun_address current_address
            SET status = 'RETIRED',
                version_no = current_address.version_no + 1,
                source_batch_id = ?,
                source_row_number = NULL,
                source_reference_date = ?,
                updated_at = CURRENT_TIMESTAMP,
                retired_at = CURRENT_TIMESTAMP
            WHERE current_address.status = 'ACTIVE'
              AND NOT EXISTS (
                  SELECT 1
                  FROM address.address_jibun_staging staging
                  WHERE staging.batch_id = ?
                    AND staging.processing_status = 'APPLIED'
                    AND
            """ + SAME_SOURCE_KEY + """
              )
            """;

    private final JdbcTemplate jdbcTemplate;

    public JibunAddressApplyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void validateApplication(UUID batchId, LocalDate referenceDate) {
        verifyBatchContext(batchId, referenceDate, "VALIDATING");

        LocalDate latestReferenceDate = jdbcTemplate.queryForObject(
                """
                SELECT max(source_reference_date)
                FROM address.address_import_batch
                WHERE source_type = 'JIBUN'
                  AND status = 'COMPLETED'
                  AND batch_id <> ?
                """,
                LocalDate.class,
                batchId
        );
        if (latestReferenceDate != null && referenceDate.isBefore(latestReferenceDate)) {
            throw preconditionFailed(
                    "최근 완료 배치보다 과거 기준일의 지번 스냅샷은 반영할 수 없습니다. latest="
                            + latestReferenceDate + ", requested=" + referenceDate
            );
        }
    }

    public ApplyCounts applyAndComplete(UUID batchId, LocalDate referenceDate) {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + JIBUN_ADDRESS_APPLY_LOCK_KEY + ")");
        verifyBatchContext(batchId, referenceDate, "APPLYING");

        long rejectedCount = rejectedCount(batchId);
        int reactivatedCount = jdbcTemplate.update(
                REACTIVATE_ADDRESSES,
                referenceDate,
                batchId
        );
        int createdCount = jdbcTemplate.update(
                INSERT_NEW_ADDRESSES,
                referenceDate,
                batchId
        );
        int appliedCount = jdbcTemplate.update(MARK_STAGING_APPLIED, batchId);

        boolean retirementSkipped = rejectedCount > 0;
        int retiredCount = 0;
        if (!retirementSkipped) {
            retiredCount = jdbcTemplate.update(
                    RETIRE_MISSING_ADDRESSES,
                    batchId,
                    referenceDate,
                    batchId
            );
        }

        long pendingCount = countStagingRows(batchId, "VALID");
        if (pendingCount != 0) {
            throw applyIncomplete("운영 테이블에 반영되지 않은 VALID 지번 행이 남아 있습니다: " + pendingCount);
        }

        ApplyCounts counts = loadApplyCounts(
                batchId,
                createdCount,
                reactivatedCount,
                retiredCount,
                retirementSkipped
        );
        if (counts.appliedCount() + counts.rejectedCount() != counts.totalCount()) {
            throw applyIncomplete("지번 전체·반영·거부 건수 합계가 일치하지 않습니다");
        }
        if (counts.appliedCount() != appliedCount) {
            throw applyIncomplete("지번 staging 반영 건수와 집계 건수가 일치하지 않습니다");
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'COMPLETED',
                    total_row_count = ?,
                    accepted_row_count = ?,
                    rejected_row_count = ?,
                    completed_at = CURRENT_TIMESTAMP,
                    failure_reason_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND source_type = 'JIBUN'
                  AND status = 'APPLYING'
                """,
                counts.totalCount(),
                counts.appliedCount(),
                counts.rejectedCount(),
                batchId
        );
        if (updated != 1) {
            throw applyIncomplete("지번 배치를 COMPLETED 상태로 변경하지 못했습니다: " + batchId);
        }
        return counts;
    }

    private void verifyBatchContext(
            UUID batchId,
            LocalDate referenceDate,
            String expectedStatus
    ) {
        BatchContext context = jdbcTemplate.queryForObject(
                """
                SELECT status, import_mode, source_reference_date
                FROM address.address_import_batch
                WHERE batch_id = ?
                  AND source_type = 'JIBUN'
                """,
                (resultSet, rowNumber) -> new BatchContext(
                        resultSet.getString("status"),
                        resultSet.getString("import_mode"),
                        resultSet.getObject("source_reference_date", LocalDate.class)
                ),
                batchId
        );
        if (context == null
                || !expectedStatus.equals(context.status())
                || !"FULL".equals(context.importMode())
                || !referenceDate.equals(context.referenceDate())) {
            throw preconditionFailed("지번 배치 상태·모드·기준일이 요청과 일치하지 않습니다: " + batchId);
        }
    }

    private long rejectedCount(UUID batchId) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT rejected_row_count
                FROM address.address_import_batch
                WHERE batch_id = ?
                """,
                Long.class,
                batchId
        );
        if (count == null) {
            throw applyIncomplete("지번 거부 건수를 조회하지 못했습니다: " + batchId);
        }
        return count;
    }

    private long countStagingRows(UUID batchId, String status) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM address.address_jibun_staging
                WHERE batch_id = ?
                  AND processing_status = ?
                """,
                Long.class,
                batchId,
                status
        );
        return count == null ? 0 : count;
    }

    private ApplyCounts loadApplyCounts(
            UUID batchId,
            long createdCount,
            long reactivatedCount,
            long retiredCount,
            boolean retirementSkipped
    ) {
        BatchCounts counts = jdbcTemplate.queryForObject(
                """
                SELECT total_row_count, rejected_row_count,
                       (SELECT count(*)
                        FROM address.address_jibun_staging
                        WHERE batch_id = ?
                          AND processing_status = 'APPLIED') AS applied_count
                FROM address.address_import_batch
                WHERE batch_id = ?
                """,
                (resultSet, rowNumber) -> new BatchCounts(
                        resultSet.getLong("total_row_count"),
                        resultSet.getLong("applied_count"),
                        resultSet.getLong("rejected_row_count")
                ),
                batchId,
                batchId
        );
        if (counts == null) {
            throw applyIncomplete("지번 반영 건수를 조회하지 못했습니다: " + batchId);
        }
        return new ApplyCounts(
                counts.totalCount(),
                counts.appliedCount(),
                counts.rejectedCount(),
                createdCount,
                reactivatedCount,
                retiredCount,
                retirementSkipped
        );
    }

    private JibunAddressImportException preconditionFailed(String message) {
        return new JibunAddressImportException(FailureCode.APPLY_PRECONDITION_FAILED, message);
    }

    private JibunAddressImportException applyIncomplete(String message) {
        return new JibunAddressImportException(FailureCode.APPLY_INCOMPLETE, message);
    }

    public record ApplyCounts(
            long totalCount,
            long appliedCount,
            long rejectedCount,
            long createdCount,
            long reactivatedCount,
            long retiredCount,
            boolean retirementSkipped
    ) {
    }

    private record BatchContext(String status, String importMode, LocalDate referenceDate) {
    }

    private record BatchCounts(long totalCount, long appliedCount, long rejectedCount) {
    }
}
