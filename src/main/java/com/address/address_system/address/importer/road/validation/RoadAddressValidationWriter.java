package com.address.address_system.address.importer.road.validation;

import com.address.address_system.address.importer.road.batch.RoadAddressImportException;
import com.address.address_system.address.importer.road.batch.RoadAddressImportFailureCode;
import com.address.address_system.address.importer.road.model.RoadAddressStagingRow;
import com.address.address_system.address.importer.road.model.RoadAddressValidationResult;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

public class RoadAddressValidationWriter implements ItemWriter<RoadAddressValidationResult> {

    private static final int MAX_REJECTED_VALUE_LENGTH = 4_000;
    private static final int MAX_REASON_DETAIL_LENGTH = 500;

    private static final String INSERT_REJECTION_SQL = """
            INSERT INTO address.address_import_rejection (
                batch_id,
                source_row_number,
                reason_code,
                field_name,
                rejected_value,
                reason_detail
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                DO NOTHING
            """;

    private static final String UPDATE_STAGING_STATUS_SQL = """
            UPDATE address.address_road_staging
            SET processing_status = ?,
                processed_at = CURRENT_TIMESTAMP
            WHERE batch_id = ?
              AND source_row_number = ?
              AND processing_status = 'LOADED'
            """;

    private static final String INSERT_DUPLICATE_MANAGEMENT_NUMBER_REJECTION_SQL = """
            WITH duplicate_management_numbers AS (
                SELECT btrim(mgmt_num) AS mgmt_num
                FROM address.address_road_staging
                WHERE batch_id = ?
                  AND processing_status = 'VALID'
                GROUP BY btrim(mgmt_num)
                HAVING count(*) > 1
            )
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
                'DUPLICATE_MANAGEMENT_NUMBER',
                'mgmt_num',
                btrim(staging.mgmt_num),
                '동일 배치에 같은 도로명주소관리번호가 중복되었습니다'
            FROM address.address_road_staging staging
            INNER JOIN duplicate_management_numbers duplicate
                ON duplicate.mgmt_num = btrim(staging.mgmt_num)
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
            ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                DO NOTHING
            """;

    private static final String INSERT_DUPLICATE_REJECTION_SQL = """
            WITH normalized_rows AS (
                SELECT
                    batch_id,
                    source_row_number,
                    btrim(mgmt_num) AS mgmt_num,
                    btrim(road_code) AS road_code,
                    btrim(underground_flag) AS underground_flag,
                    COALESCE(NULLIF(ltrim(btrim(build_main), '0'), ''), '0') AS build_main,
                    COALESCE(NULLIF(ltrim(btrim(build_sub), '0'), ''), '0') AS build_sub
                FROM address.address_road_staging
                WHERE batch_id = ?
                  AND processing_status = 'VALID'
            ),
            duplicate_keys AS (
                SELECT
                    mgmt_num,
                    road_code,
                    underground_flag,
                    build_main,
                    build_sub
                FROM normalized_rows
                GROUP BY
                    mgmt_num,
                    road_code,
                    underground_flag,
                    build_main,
                    build_sub
                HAVING count(*) > 1
            )
            INSERT INTO address.address_import_rejection (
                batch_id,
                source_row_number,
                reason_code,
                field_name,
                rejected_value,
                reason_detail
            )
            SELECT
                row.batch_id,
                row.source_row_number,
                'DUPLICATE_ADDRESS_KEY',
                NULL,
                concat_ws(
                    '|',
                    row.mgmt_num,
                    row.road_code,
                    row.underground_flag,
                    row.build_main,
                    row.build_sub
                ),
                '동일 배치에 공식 도로명주소 복합 식별키가 중복되었습니다'
            FROM normalized_rows row
            INNER JOIN duplicate_keys duplicate
                USING (mgmt_num, road_code, underground_flag, build_main, build_sub)
            ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                DO NOTHING
            """;

    private static final String REJECT_DUPLICATE_STAGING_ROWS_SQL = """
            UPDATE address.address_road_staging staging
            SET processing_status = 'REJECTED',
                processed_at = CURRENT_TIMESTAMP
            WHERE staging.batch_id = ?
              AND staging.processing_status = 'VALID'
              AND EXISTS (
                  SELECT 1
                  FROM address.address_import_rejection rejection
                  WHERE rejection.batch_id = staging.batch_id
                    AND rejection.source_row_number = staging.source_row_number
                    AND rejection.reason_code IN (
                        'DUPLICATE_MANAGEMENT_NUMBER',
                        'DUPLICATE_ADDRESS_KEY'
                    )
              )
            """;

    private final JdbcTemplate jdbcTemplate;

    public RoadAddressValidationWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void write(Chunk<? extends RoadAddressValidationResult> chunk) {
        List<? extends RoadAddressValidationResult> results = chunk.getItems();
        ensureSingleBatch(results);

        List<Rejection> rejections = results.stream()
                .flatMap(result -> result.violations().stream()
                        .map(violation -> new Rejection(
                                result.row().batchId(),
                                result.row().sourceRowNumber(),
                                violation
                        )))
                .toList();

        if (!rejections.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    INSERT_REJECTION_SQL,
                    rejections,
                    rejections.size(),
                    this::setRejectionParameters
            );
        }

        if (!results.isEmpty()) {
            int[] updateCounts = jdbcTemplate.batchUpdate(
                    UPDATE_STAGING_STATUS_SQL,
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement statement, int index)
                                throws SQLException {
                            RoadAddressValidationResult result = results.get(index);
                            statement.setString(1, result.isValid() ? "VALID" : "REJECTED");
                            statement.setObject(2, result.row().batchId());
                            statement.setLong(3, result.row().sourceRowNumber());
                        }

                        @Override
                        public int getBatchSize() {
                            return results.size();
                        }
                    }
            );
            verifyStatusUpdates(results, updateCounts);
        }
    }

    public void rejectDuplicateAddressKeys(UUID batchId) {
        jdbcTemplate.update(
                INSERT_DUPLICATE_MANAGEMENT_NUMBER_REJECTION_SQL,
                batchId,
                batchId
        );
        jdbcTemplate.update(INSERT_DUPLICATE_REJECTION_SQL, batchId);
        jdbcTemplate.update(REJECT_DUPLICATE_STAGING_ROWS_SQL, batchId);
    }

    private void setRejectionParameters(PreparedStatement statement, Rejection rejection)
            throws SQLException {
        RoadAddressValidationResult.Violation violation = rejection.violation();
        statement.setObject(1, rejection.batchId());
        statement.setLong(2, rejection.sourceRowNumber());
        statement.setString(3, violation.reasonCode());
        statement.setString(4, violation.fieldName());
        statement.setString(5, truncate(violation.rejectedValue(), MAX_REJECTED_VALUE_LENGTH));
        statement.setString(6, truncate(violation.reasonDetail(), MAX_REASON_DETAIL_LENGTH));
    }

    private void ensureSingleBatch(List<? extends RoadAddressValidationResult> results) {
        Set<UUID> batchIds = results.stream()
                .map(result -> result.row().batchId())
                .collect(java.util.stream.Collectors.toSet());
        if (batchIds.size() > 1) {
            throw new IllegalStateException("하나의 청크에 서로 다른 주소 검증 배치가 포함되었습니다");
        }
    }

    private void verifyStatusUpdates(
            List<? extends RoadAddressValidationResult> results,
            int[] updateCounts
    ) {
        if (updateCounts.length != results.size()) {
            throw validationStateConflict("검증 행 상태 변경 결과 수가 요청 수와 일치하지 않습니다");
        }
        for (int index = 0; index < updateCounts.length; index++) {
            int updateCount = updateCounts[index];
            if (updateCount != 1 && updateCount != Statement.SUCCESS_NO_INFO) {
                RoadAddressStagingRow row = results.get(index).row();
                throw validationStateConflict(
                        "LOADED 상태의 검증 대상 행을 변경하지 못했습니다. batchId="
                                + row.batchId() + ", sourceRowNumber=" + row.sourceRowNumber()
                );
            }
        }
    }

    private RoadAddressImportException validationStateConflict(String message) {
        return new RoadAddressImportException(
                RoadAddressImportFailureCode.VALIDATION_STATE_CONFLICT,
                message
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record Rejection(
            UUID batchId,
            long sourceRowNumber,
            RoadAddressValidationResult.Violation violation
    ) {
    }
}
