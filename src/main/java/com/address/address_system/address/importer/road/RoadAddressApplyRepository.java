package com.address.address_system.address.importer.road;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

public class RoadAddressApplyRepository {

    private static final String APPLY_REJECTION_PREFIX = "APPLY_%";
    private static final long ROAD_ADDRESS_APPLY_LOCK_KEY = 8_123_476_102_948_112L;

    private static final String CURRENT_ROW_EQUALS_STAGING = """
            current_address.legal_area_code = btrim(staging.legal_area_code)
            AND current_address.legal_dong_code IS NOT DISTINCT FROM
                NULLIF(btrim(staging.legal_dong_code), '')
            AND current_address.sido = btrim(staging.sido)
            AND current_address.sigungu IS NOT DISTINCT FROM NULLIF(btrim(staging.sigungu), '')
            AND current_address.b_dong_name IS NOT DISTINCT FROM NULLIF(btrim(staging.b_dong_name), '')
            AND current_address.road_code = btrim(staging.road_code)
            AND current_address.road_name = btrim(staging.road_name)
            AND current_address.underground_flag = btrim(staging.underground_flag)::smallint
            AND current_address.build_main = btrim(staging.build_main)::integer
            AND current_address.build_sub = btrim(staging.build_sub)::integer
            AND current_address.zip_code = btrim(staging.zip_code)
            AND current_address.apartment_status = CASE btrim(staging.apartment_flag)
                WHEN '1' THEN 'APARTMENT'
                WHEN '0' THEN 'NON_APARTMENT'
                ELSE 'UNKNOWN'
            END
            AND current_address.build_name_official IS NOT DISTINCT FROM
                NULLIF(btrim(staging.build_nm_official), '')
            AND current_address.build_name_sigungu IS NOT DISTINCT FROM
                NULLIF(btrim(staging.build_nm_sgg), '')
            AND current_address.effective_date =
                to_date(btrim(staging.effective_date), 'YYYYMMDD')
            """;

    private static final String INSERT_STALE_CHANGE_REJECTIONS = """
            INSERT INTO address.address_import_rejection (
                batch_id,
                source_row_number,
                reason_code,
                field_name,
                rejected_value,
                reason_detail
            )
            SELECT
                staging.batch_id,
                staging.source_row_number,
                'APPLY_STALE_CHANGE',
                'effective_date',
                staging.effective_date,
                '현재 운영 주소보다 과거 효력발생일의 변경분입니다'
            FROM address.address_road_staging staging
            INNER JOIN address.road_address current_address
                ON current_address.mgmt_num = btrim(staging.mgmt_num)
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND to_date(btrim(staging.effective_date), 'YYYYMMDD')
                    < current_address.effective_date
            ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                DO NOTHING
            """;

    private static final String INSERT_MISSING_TARGET_REJECTIONS = """
            INSERT INTO address.address_import_rejection (
                batch_id,
                source_row_number,
                reason_code,
                field_name,
                rejected_value,
                reason_detail
            )
            SELECT
                staging.batch_id,
                staging.source_row_number,
                'APPLY_TARGET_NOT_FOUND',
                'mgmt_num',
                staging.mgmt_num,
                '수정 또는 폐지할 운영 도로명주소가 존재하지 않습니다'
            FROM address.address_road_staging staging
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND btrim(staging.movement_reason_code) IN ('34', '63')
              AND NOT EXISTS (
                  SELECT 1
                  FROM address.road_address current_address
                  WHERE current_address.mgmt_num = btrim(staging.mgmt_num)
              )
            ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                DO NOTHING
            """;

    private static final String INSERT_INACTIVE_UPDATE_REJECTIONS = """
            INSERT INTO address.address_import_rejection (
                batch_id,
                source_row_number,
                reason_code,
                field_name,
                rejected_value,
                reason_detail
            )
            SELECT
                staging.batch_id,
                staging.source_row_number,
                'APPLY_TARGET_INACTIVE',
                'mgmt_num',
                staging.mgmt_num,
                '폐지된 운영 주소는 수정할 수 없습니다'
            FROM address.address_road_staging staging
            INNER JOIN address.road_address current_address
                ON current_address.mgmt_num = btrim(staging.mgmt_num)
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND btrim(staging.movement_reason_code) = '34'
              AND current_address.status = 'RETIRED'
            ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                DO NOTHING
            """;

    private static final String INSERT_ACTIVE_CREATE_CONFLICT_REJECTIONS = """
            INSERT INTO address.address_import_rejection (
                batch_id,
                source_row_number,
                reason_code,
                field_name,
                rejected_value,
                reason_detail
            )
            SELECT
                staging.batch_id,
                staging.source_row_number,
                'APPLY_ACTIVE_CONFLICT',
                'mgmt_num',
                staging.mgmt_num,
                '신규 주소의 관리번호가 기존 활성 주소와 충돌합니다'
            FROM address.address_road_staging staging
            INNER JOIN address.road_address current_address
                ON current_address.mgmt_num = btrim(staging.mgmt_num)
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND btrim(staging.movement_reason_code) = '31'
              AND current_address.status = 'ACTIVE'
              AND NOT (
            """ + CURRENT_ROW_EQUALS_STAGING + """
              )
            ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                DO NOTHING
            """;

    private static final String INSERT_REACTIVATION_DATE_CONFLICT_REJECTIONS = """
            INSERT INTO address.address_import_rejection (
                batch_id,
                source_row_number,
                reason_code,
                field_name,
                rejected_value,
                reason_detail
            )
            SELECT
                staging.batch_id,
                staging.source_row_number,
                'APPLY_REACTIVATION_DATE_CONFLICT',
                'effective_date',
                staging.effective_date,
                '폐지 주소 재활성화는 기존 효력발생일보다 이후여야 합니다'
            FROM address.address_road_staging staging
            INNER JOIN address.road_address current_address
                ON current_address.mgmt_num = btrim(staging.mgmt_num)
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND btrim(staging.movement_reason_code) = '31'
              AND current_address.status = 'RETIRED'
              AND to_date(btrim(staging.effective_date), 'YYYYMMDD')
                    <= current_address.effective_date
            ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                DO NOTHING
            """;

    private static final String REJECT_APPLICATION_ROWS = """
            UPDATE address.address_road_staging staging
            SET processing_status = 'REJECTED'
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND EXISTS (
                  SELECT 1
                  FROM address.address_import_rejection rejection
                  WHERE rejection.batch_id = staging.batch_id
                    AND rejection.source_row_number = staging.source_row_number
                    AND rejection.reason_code LIKE ?
              )
            """;

    private static final String INSERT_FULL_ADDRESSES = """
            INSERT INTO address.road_address (
                mgmt_num,
                legal_area_code,
                legal_dong_code,
                sido,
                sigungu,
                b_dong_name,
                road_code,
                road_name,
                underground_flag,
                build_main,
                build_sub,
                zip_code,
                apartment_status,
                build_name_official,
                build_name_sigungu,
                status,
                effective_date,
                last_movement_reason_code,
                source_batch_id,
                source_row_number
            )
            SELECT
                btrim(staging.mgmt_num),
                btrim(staging.legal_area_code),
                NULLIF(btrim(staging.legal_dong_code), ''),
                btrim(staging.sido),
                NULLIF(btrim(staging.sigungu), ''),
                NULLIF(btrim(staging.b_dong_name), ''),
                btrim(staging.road_code),
                btrim(staging.road_name),
                btrim(staging.underground_flag)::smallint,
                btrim(staging.build_main)::integer,
                btrim(staging.build_sub)::integer,
                btrim(staging.zip_code),
                CASE btrim(staging.apartment_flag)
                    WHEN '1' THEN 'APARTMENT'
                    WHEN '0' THEN 'NON_APARTMENT'
                    ELSE 'UNKNOWN'
                END,
                NULLIF(btrim(staging.build_nm_official), ''),
                NULLIF(btrim(staging.build_nm_sgg), ''),
                'ACTIVE',
                COALESCE(
                    to_date(NULLIF(btrim(staging.effective_date), ''), 'YYYYMMDD'),
                    ?
                ),
                NULLIF(btrim(staging.movement_reason_code), ''),
                staging.batch_id,
                staging.source_row_number
            FROM address.address_road_staging staging
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
            """;

    private static final String INSERT_DELTA_ADDRESSES = """
            INSERT INTO address.road_address (
                mgmt_num,
                legal_area_code,
                legal_dong_code,
                sido,
                sigungu,
                b_dong_name,
                road_code,
                road_name,
                underground_flag,
                build_main,
                build_sub,
                zip_code,
                apartment_status,
                build_name_official,
                build_name_sigungu,
                status,
                effective_date,
                last_movement_reason_code,
                source_batch_id,
                source_row_number
            )
            SELECT
                btrim(staging.mgmt_num),
                btrim(staging.legal_area_code),
                NULLIF(btrim(staging.legal_dong_code), ''),
                btrim(staging.sido),
                NULLIF(btrim(staging.sigungu), ''),
                NULLIF(btrim(staging.b_dong_name), ''),
                btrim(staging.road_code),
                btrim(staging.road_name),
                btrim(staging.underground_flag)::smallint,
                btrim(staging.build_main)::integer,
                btrim(staging.build_sub)::integer,
                btrim(staging.zip_code),
                CASE btrim(staging.apartment_flag)
                    WHEN '1' THEN 'APARTMENT'
                    WHEN '0' THEN 'NON_APARTMENT'
                    ELSE 'UNKNOWN'
                END,
                NULLIF(btrim(staging.build_nm_official), ''),
                NULLIF(btrim(staging.build_nm_sgg), ''),
                'ACTIVE',
                to_date(btrim(staging.effective_date), 'YYYYMMDD'),
                '31',
                staging.batch_id,
                staging.source_row_number
            FROM address.address_road_staging staging
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND btrim(staging.movement_reason_code) = '31'
              AND NOT EXISTS (
                  SELECT 1
                  FROM address.road_address current_address
                  WHERE current_address.mgmt_num = btrim(staging.mgmt_num)
              )
            """;

    private static final String REACTIVATE_DELTA_ADDRESSES = """
            UPDATE address.road_address current_address
            SET legal_area_code = btrim(staging.legal_area_code),
                legal_dong_code = NULLIF(btrim(staging.legal_dong_code), ''),
                sido = btrim(staging.sido),
                sigungu = NULLIF(btrim(staging.sigungu), ''),
                b_dong_name = NULLIF(btrim(staging.b_dong_name), ''),
                road_code = btrim(staging.road_code),
                road_name = btrim(staging.road_name),
                underground_flag = btrim(staging.underground_flag)::smallint,
                build_main = btrim(staging.build_main)::integer,
                build_sub = btrim(staging.build_sub)::integer,
                zip_code = btrim(staging.zip_code),
                apartment_status = CASE btrim(staging.apartment_flag)
                    WHEN '1' THEN 'APARTMENT'
                    WHEN '0' THEN 'NON_APARTMENT'
                    ELSE 'UNKNOWN'
                END,
                build_name_official = NULLIF(btrim(staging.build_nm_official), ''),
                build_name_sigungu = NULLIF(btrim(staging.build_nm_sgg), ''),
                status = 'ACTIVE',
                effective_date = to_date(btrim(staging.effective_date), 'YYYYMMDD'),
                last_movement_reason_code = '31',
                version_no = current_address.version_no + 1,
                source_batch_id = staging.batch_id,
                source_row_number = staging.source_row_number,
                updated_at = CURRENT_TIMESTAMP,
                retired_at = NULL
            FROM address.address_road_staging staging
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND btrim(staging.movement_reason_code) = '31'
              AND current_address.mgmt_num = btrim(staging.mgmt_num)
              AND current_address.status = 'RETIRED'
              AND to_date(btrim(staging.effective_date), 'YYYYMMDD')
                    > current_address.effective_date
            """;

    private static final String UPDATE_DELTA_ADDRESSES = """
            UPDATE address.road_address current_address
            SET legal_area_code = btrim(staging.legal_area_code),
                legal_dong_code = NULLIF(btrim(staging.legal_dong_code), ''),
                sido = btrim(staging.sido),
                sigungu = NULLIF(btrim(staging.sigungu), ''),
                b_dong_name = NULLIF(btrim(staging.b_dong_name), ''),
                road_code = btrim(staging.road_code),
                road_name = btrim(staging.road_name),
                underground_flag = btrim(staging.underground_flag)::smallint,
                build_main = btrim(staging.build_main)::integer,
                build_sub = btrim(staging.build_sub)::integer,
                zip_code = btrim(staging.zip_code),
                apartment_status = CASE btrim(staging.apartment_flag)
                    WHEN '1' THEN 'APARTMENT'
                    WHEN '0' THEN 'NON_APARTMENT'
                    ELSE 'UNKNOWN'
                END,
                build_name_official = NULLIF(btrim(staging.build_nm_official), ''),
                build_name_sigungu = NULLIF(btrim(staging.build_nm_sgg), ''),
                effective_date = to_date(btrim(staging.effective_date), 'YYYYMMDD'),
                last_movement_reason_code = '34',
                version_no = current_address.version_no + 1,
                source_batch_id = staging.batch_id,
                source_row_number = staging.source_row_number,
                updated_at = CURRENT_TIMESTAMP
            FROM address.address_road_staging staging
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND btrim(staging.movement_reason_code) = '34'
              AND current_address.mgmt_num = btrim(staging.mgmt_num)
              AND current_address.status = 'ACTIVE'
              AND to_date(btrim(staging.effective_date), 'YYYYMMDD')
                    >= current_address.effective_date
              AND NOT (
            """ + CURRENT_ROW_EQUALS_STAGING + """
              )
            """;

    private static final String RETIRE_DELTA_ADDRESSES = """
            UPDATE address.road_address current_address
            SET status = 'RETIRED',
                effective_date = to_date(btrim(staging.effective_date), 'YYYYMMDD'),
                last_movement_reason_code = '63',
                version_no = current_address.version_no + 1,
                source_batch_id = staging.batch_id,
                source_row_number = staging.source_row_number,
                updated_at = CURRENT_TIMESTAMP,
                retired_at = CURRENT_TIMESTAMP
            FROM address.address_road_staging staging
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND btrim(staging.movement_reason_code) = '63'
              AND current_address.mgmt_num = btrim(staging.mgmt_num)
              AND current_address.status = 'ACTIVE'
              AND to_date(btrim(staging.effective_date), 'YYYYMMDD')
                    >= current_address.effective_date
            """;

    private static final String MARK_FULL_ROWS_APPLIED = """
            UPDATE address.address_road_staging
            SET processing_status = 'APPLIED',
                apply_action = 'CREATED',
                applied_at = CURRENT_TIMESTAMP
            WHERE batch_id = ?
              AND processing_status = 'VALID'
            """;

    private static final String MARK_DELTA_ROWS_APPLIED = """
            UPDATE address.address_road_staging staging
            SET processing_status = 'APPLIED',
                apply_action = CASE
                    WHEN current_address.source_batch_id = staging.batch_id
                     AND current_address.source_row_number = staging.source_row_number
                    THEN CASE btrim(staging.movement_reason_code)
                        WHEN '31' THEN CASE
                            WHEN current_address.version_no = 1 THEN 'CREATED'
                            ELSE 'REACTIVATED'
                        END
                        WHEN '34' THEN 'UPDATED'
                        WHEN '63' THEN 'RETIRED'
                    END
                    ELSE 'NO_CHANGE'
                END,
                applied_at = CURRENT_TIMESTAMP
            FROM address.road_address current_address
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND current_address.mgmt_num = btrim(staging.mgmt_num)
            """;

    private final JdbcTemplate jdbcTemplate;

    public RoadAddressApplyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void validateApplication(
            UUID batchId,
            RoadAddressImportMode importMode,
            LocalDate referenceDate
    ) {
        verifyBatchContext(batchId, importMode, referenceDate, "VALIDATING");
        verifyModePreconditions(batchId, importMode, referenceDate);

        if (importMode == RoadAddressImportMode.DELTA) {
            jdbcTemplate.update(INSERT_STALE_CHANGE_REJECTIONS, batchId);
            jdbcTemplate.update(INSERT_MISSING_TARGET_REJECTIONS, batchId);
            jdbcTemplate.update(INSERT_INACTIVE_UPDATE_REJECTIONS, batchId);
            jdbcTemplate.update(INSERT_ACTIVE_CREATE_CONFLICT_REJECTIONS, batchId);
            jdbcTemplate.update(INSERT_REACTIVATION_DATE_CONFLICT_REJECTIONS, batchId);
            jdbcTemplate.update(REJECT_APPLICATION_ROWS, batchId, APPLY_REJECTION_PREFIX);
        }
    }

    public ApplyCounts applyAndComplete(
            UUID batchId,
            RoadAddressImportMode importMode,
            LocalDate referenceDate
    ) {
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + ROAD_ADDRESS_APPLY_LOCK_KEY + ")");
        verifyBatchContext(batchId, importMode, referenceDate, "APPLYING");
        verifyModePreconditions(batchId, importMode, referenceDate);

        if (importMode == RoadAddressImportMode.FULL) {
            applyFull(batchId, referenceDate);
        }
        else {
            applyDelta(batchId);
        }

        long pendingCount = countStagingRows(batchId, "VALID");
        if (pendingCount != 0) {
            throw applyIncomplete(
                    "운영 주소에 반영되지 않은 VALID 행이 남아 있습니다. "
                            + "batchId=" + batchId + ", count=" + pendingCount
            );
        }

        ApplyCounts counts = loadApplyCounts(batchId);
        if (counts.appliedCount() + counts.rejectedCount() != counts.totalCount()) {
            throw applyIncomplete(
                    "전체·반영·거부 행 집계가 일치하지 않습니다. batchId=" + batchId
            );
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
                  AND status = 'APPLYING'
                """,
                counts.totalCount(),
                counts.appliedCount(),
                counts.rejectedCount(),
                batchId
        );
        if (updated != 1) {
            throw applyIncomplete("적재 배치를 완료 상태로 변경하지 못했습니다. batchId=" + batchId);
        }
        return counts;
    }

    private void applyFull(UUID batchId, LocalDate referenceDate) {
        int inserted = jdbcTemplate.update(INSERT_FULL_ADDRESSES, referenceDate, batchId);
        int applied = jdbcTemplate.update(MARK_FULL_ROWS_APPLIED, batchId);
        if (inserted != applied) {
            throw applyIncomplete(
                    "FULL 주소 생성 건수와 반영 상태 변경 건수가 일치하지 않습니다. "
                            + "inserted=" + inserted + ", applied=" + applied
            );
        }
    }

    private void applyDelta(UUID batchId) {
        jdbcTemplate.update(INSERT_DELTA_ADDRESSES, batchId);
        jdbcTemplate.update(REACTIVATE_DELTA_ADDRESSES, batchId);
        jdbcTemplate.update(UPDATE_DELTA_ADDRESSES, batchId);
        jdbcTemplate.update(RETIRE_DELTA_ADDRESSES, batchId);
        jdbcTemplate.update(MARK_DELTA_ROWS_APPLIED, batchId);
    }

    private void verifyModePreconditions(
            UUID batchId,
            RoadAddressImportMode importMode,
            LocalDate referenceDate
    ) {
        Long addressCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM address.road_address",
                Long.class
        );
        long currentAddressCount = addressCount == null ? 0 : addressCount;

        if (importMode == RoadAddressImportMode.FULL) {
            if (currentAddressCount != 0) {
                throw preconditionFailed("FULL 적재는 운영 주소 원장이 비어 있을 때만 허용됩니다");
            }
            return;
        }

        if (currentAddressCount == 0) {
            throw preconditionFailed("DELTA 적재 전에 FULL 기준 데이터가 필요합니다");
        }

        LocalDate latestReferenceDate = jdbcTemplate.queryForObject(
                """
                SELECT max(source_reference_date)
                FROM address.address_import_batch
                WHERE source_type = 'ROAD'
                  AND status = 'COMPLETED'
                  AND batch_id <> ?
                """,
                LocalDate.class,
                batchId
        );
        if (latestReferenceDate != null && referenceDate.isBefore(latestReferenceDate)) {
            throw preconditionFailed(
                    "최근 완료 배치보다 과거 기준일의 DELTA 파일은 반영할 수 없습니다. "
                            + "latest=" + latestReferenceDate + ", requested=" + referenceDate
            );
        }
    }

    private void verifyBatchContext(
            UUID batchId,
            RoadAddressImportMode importMode,
            LocalDate referenceDate,
            String expectedStatus
    ) {
        BatchContext context = jdbcTemplate.queryForObject(
                """
                SELECT status, import_mode, source_reference_date
                FROM address.address_import_batch
                WHERE batch_id = ?
                """,
                (resultSet, rowNumber) -> new BatchContext(
                        resultSet.getString("status"),
                        RoadAddressImportMode.valueOf(resultSet.getString("import_mode")),
                        resultSet.getObject("source_reference_date", LocalDate.class)
                ),
                batchId
        );
        if (context == null
                || !expectedStatus.equals(context.status())
                || importMode != context.importMode()
                || !referenceDate.equals(context.referenceDate())) {
            throw preconditionFailed(
                    "배치 상태·실행 모드·기준일이 요청과 일치하지 않습니다. batchId=" + batchId
            );
        }
    }

    private long countStagingRows(UUID batchId, String processingStatus) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM address.address_road_staging
                WHERE batch_id = ?
                  AND processing_status = ?
                """,
                Long.class,
                batchId,
                processingStatus
        );
        return count == null ? 0 : count;
    }

    private ApplyCounts loadApplyCounts(UUID batchId) {
        ApplyCounts counts = jdbcTemplate.queryForObject(
                """
                SELECT
                    (
                        SELECT count(*)
                        FROM address.address_road_staging
                        WHERE batch_id = ?
                    ) + (
                        SELECT count(DISTINCT rejection.source_row_number)
                        FROM address.address_import_rejection rejection
                        WHERE rejection.batch_id = ?
                          AND NOT EXISTS (
                              SELECT 1
                              FROM address.address_road_staging staging
                              WHERE staging.batch_id = rejection.batch_id
                                AND staging.source_row_number = rejection.source_row_number
                          )
                    ) AS total_count,
                    (
                        SELECT count(*)
                        FROM address.address_road_staging
                        WHERE batch_id = ?
                          AND processing_status = 'APPLIED'
                    ) AS applied_count,
                    (
                        SELECT count(DISTINCT source_row_number)
                        FROM address.address_import_rejection
                        WHERE batch_id = ?
                    ) AS rejected_count
                """,
                (resultSet, rowNumber) -> new ApplyCounts(
                        resultSet.getLong("total_count"),
                        resultSet.getLong("applied_count"),
                        resultSet.getLong("rejected_count")
                ),
                batchId,
                batchId,
                batchId,
                batchId
        );
        if (counts == null) {
            throw applyIncomplete("운영 주소 반영 건수를 조회하지 못했습니다. batchId=" + batchId);
        }
        return counts;
    }

    private RoadAddressImportException preconditionFailed(String message) {
        return new RoadAddressImportException(
                RoadAddressImportFailureCode.APPLY_PRECONDITION_FAILED,
                message
        );
    }

    private RoadAddressImportException applyIncomplete(String message) {
        return new RoadAddressImportException(
                RoadAddressImportFailureCode.APPLY_INCOMPLETE,
                message
        );
    }

    public record ApplyCounts(long totalCount, long appliedCount, long rejectedCount) {
    }

    private record BatchContext(
            String status,
            RoadAddressImportMode importMode,
            LocalDate referenceDate
    ) {
    }
}
