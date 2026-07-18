package com.address.address_system.address.importer.road;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class RoadAddressImportBatchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public RoadAddressImportBatchRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public RegisteredBatch registerOrResume(
            RoadAddressImportFile file,
            RoadAddressImportMode importMode,
            LocalDate referenceDate
    ) {
        try {
            return transactionTemplate.execute(status -> {
                List<RegisteredBatch> existing = jdbcTemplate.query(
                        """
                        SELECT batch_id, status, import_mode, source_reference_date
                        FROM address.address_import_batch
                        WHERE source_type = 'ROAD'
                          AND source_file_sha256 = ?
                        """,
                        (resultSet, rowNumber) -> new RegisteredBatch(
                                resultSet.getObject("batch_id", UUID.class),
                                resultSet.getString("status"),
                                RoadAddressImportMode.valueOf(resultSet.getString("import_mode")),
                                resultSet.getObject("source_reference_date", LocalDate.class)
                        ),
                        file.sha256()
                );

                if (!existing.isEmpty()) {
                    RegisteredBatch batch = existing.get(0);
                    if (batch.importMode() != importMode
                            || !batch.referenceDate().equals(referenceDate)) {
                        throw duplicateSourceFile();
                    }
                    if ("FAILED".equals(batch.status()) || "REGISTERED".equals(batch.status())) {
                        return batch;
                    }
                    throw duplicateSourceFile();
                }

                UUID batchId = UUID.randomUUID();
                jdbcTemplate.update(
                        """
                        INSERT INTO address.address_import_batch (
                            batch_id,
                            source_type,
                            source_file_name,
                            source_file_sha256,
                            source_reference_date,
                            file_size_bytes,
                            import_mode,
                            status
                        ) VALUES (?, 'ROAD', ?, ?, ?, ?, ?, 'REGISTERED')
                        """,
                        batchId,
                        file.fileName(),
                        file.sha256(),
                        referenceDate,
                        file.fileSizeBytes(),
                        importMode.name()
                );
                return new RegisteredBatch(batchId, "REGISTERED", importMode, referenceDate);
            });
        }
        catch (DuplicateKeyException exception) {
            throw duplicateSourceFile(exception);
        }
    }

    public void markLoading(UUID batchId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'LOADING',
                    started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                    completed_at = NULL,
                    failure_reason_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND status IN ('REGISTERED', 'FAILED')
                """,
                batchId
        );
        if (updated != 1) {
            throw new IllegalStateException("적재 배치를 LOADING 상태로 변경하지 못했습니다: " + batchId);
        }
    }

    public LoadCounts markReadyForValidation(UUID batchId) {
        LoadCounts counts = jdbcTemplate.queryForObject(
                """
                SELECT
                    (SELECT count(*) FROM address.address_road_staging WHERE batch_id = ?) AS staging_count,
                    (
                        SELECT count(DISTINCT rejection.source_row_number)
                        FROM address.address_import_rejection rejection
                        WHERE rejection.batch_id = ?
                          AND NOT EXISTS (
                              SELECT 1
                              FROM address.address_road_staging staging
                              WHERE staging.batch_id = rejection.batch_id
                                AND staging.source_row_number = rejection.source_row_number
                          )
                    ) AS parser_rejection_count
                """,
                (resultSet, rowNumber) -> new LoadCounts(
                        resultSet.getLong("staging_count"),
                        resultSet.getLong("parser_rejection_count")
                ),
                batchId,
                batchId
        );
        if (counts == null) {
            throw new IllegalStateException("적재 배치 건수를 조회하지 못했습니다: " + batchId);
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'VALIDATING',
                    total_row_count = ?,
                    accepted_row_count = NULL,
                    rejected_row_count = ?,
                    completed_at = NULL,
                    failure_reason_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND status = 'LOADING'
                """,
                counts.totalCount(),
                counts.parserRejectedCount(),
                batchId
        );
        if (updated != 1) {
            throw new IllegalStateException("적재 배치를 VALIDATING 상태로 변경하지 못했습니다: " + batchId);
        }
        return counts;
    }

    public ImportCounts markReadyForApply(UUID batchId, int maxSkippedRows) {
        ValidationCounts counts = jdbcTemplate.queryForObject(
                """
                SELECT
                    (SELECT count(*)
                     FROM address.address_road_staging
                     WHERE batch_id = ?) AS staging_count,
                    (SELECT count(*)
                     FROM address.address_road_staging
                     WHERE batch_id = ?
                       AND processing_status = 'VALID') AS accepted_count,
                    (SELECT count(*)
                     FROM address.address_road_staging
                     WHERE batch_id = ?
                       AND processing_status = 'LOADED') AS pending_count,
                    (
                        SELECT count(*)
                        FROM address.address_road_staging staging
                        WHERE staging.batch_id = ?
                          AND (
                              (
                                  staging.processing_status = 'REJECTED'
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM address.address_import_rejection rejection
                                      WHERE rejection.batch_id = staging.batch_id
                                        AND rejection.source_row_number = staging.source_row_number
                                  )
                              )
                              OR (
                                  staging.processing_status = 'VALID'
                                  AND EXISTS (
                                      SELECT 1
                                      FROM address.address_import_rejection rejection
                                      WHERE rejection.batch_id = staging.batch_id
                                        AND rejection.source_row_number = staging.source_row_number
                                  )
                              )
                          )
                    ) AS inconsistent_count,
                    (
                        SELECT count(DISTINCT rejection.source_row_number)
                        FROM address.address_import_rejection rejection
                        WHERE rejection.batch_id = ?
                    ) AS rejected_count,
                    (
                        SELECT count(DISTINCT rejection.source_row_number)
                        FROM address.address_import_rejection rejection
                        WHERE rejection.batch_id = ?
                          AND NOT EXISTS (
                              SELECT 1
                              FROM address.address_road_staging staging
                              WHERE staging.batch_id = rejection.batch_id
                                AND staging.source_row_number = rejection.source_row_number
                          )
                    ) AS parser_rejection_count
                """,
                (resultSet, rowNumber) -> new ValidationCounts(
                        resultSet.getLong("staging_count"),
                        resultSet.getLong("accepted_count"),
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("inconsistent_count"),
                        resultSet.getLong("rejected_count"),
                        resultSet.getLong("parser_rejection_count")
                ),
                batchId,
                batchId,
                batchId,
                batchId,
                batchId,
                batchId
        );
        if (counts == null) {
            throw validationIncomplete("검증 완료 건수를 조회하지 못했습니다. batchId=" + batchId);
        }

        ImportCounts importCounts = new ImportCounts(
                counts.totalCount(),
                counts.acceptedCount(),
                counts.rejectedCount()
        );
        if (counts.pendingCount() != 0) {
            throw validationIncomplete(
                    "검증되지 않은 LOADED 행이 " + counts.pendingCount() + "건 남아 있습니다. batchId=" + batchId
            );
        }
        if (counts.inconsistentCount() != 0) {
            throw validationIncomplete(
                    "검증 상태와 검역 사유가 일치하지 않는 행이 "
                            + counts.inconsistentCount() + "건입니다. batchId=" + batchId
            );
        }
        if (importCounts.acceptedCount() + importCounts.rejectedCount()
                != importCounts.totalCount()) {
            throw validationIncomplete(
                    "전체·정상·거부 행 집계가 일치하지 않습니다. batchId=" + batchId
            );
        }
        if (importCounts.rejectedCount() > maxSkippedRows) {
            throw new RoadAddressImportException(
                    RoadAddressImportFailureCode.SKIP_LIMIT_EXCEEDED,
                    "검역 대상 행이 허용 한도 " + maxSkippedRows + "건을 초과했습니다"
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'APPLYING',
                    total_row_count = ?,
                    accepted_row_count = ?,
                    rejected_row_count = ?,
                    completed_at = NULL,
                    failure_reason_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND status = 'VALIDATING'
                """,
                importCounts.totalCount(),
                importCounts.acceptedCount(),
                importCounts.rejectedCount(),
                batchId
        );
        if (updated != 1) {
            throw validationIncomplete(
                    "적재 배치를 APPLYING 상태로 변경하지 못했습니다. batchId=" + batchId
            );
        }
        return importCounts;
    }

    public ImportCounts getCompletedCounts(UUID batchId) {
        CompletedBatch batch = jdbcTemplate.queryForObject(
                """
                SELECT status, total_row_count, accepted_row_count, rejected_row_count
                FROM address.address_import_batch
                WHERE batch_id = ?
                """,
                (resultSet, rowNumber) -> new CompletedBatch(
                        resultSet.getString("status"),
                        resultSet.getObject("total_row_count", Long.class),
                        resultSet.getObject("accepted_row_count", Long.class),
                        resultSet.getObject("rejected_row_count", Long.class)
                ),
                batchId
        );
        if (batch == null
                || !"COMPLETED".equals(batch.status())
                || batch.totalCount() == null
                || batch.acceptedCount() == null
                || batch.rejectedCount() == null) {
            throw validationIncomplete(
                    "완료된 적재 배치의 최종 건수를 조회하지 못했습니다. batchId=" + batchId
            );
        }
        return new ImportCounts(
                batch.totalCount(),
                batch.acceptedCount(),
                batch.rejectedCount()
        );
    }

    public void markFailed(UUID batchId, RoadAddressImportFailureCode failureCode) {
        jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'FAILED',
                    completed_at = CURRENT_TIMESTAMP,
                    failure_reason_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                """,
                failureCode.name(),
                batchId
        );
    }

    public record RegisteredBatch(
            UUID batchId,
            String status,
            RoadAddressImportMode importMode,
            LocalDate referenceDate
    ) {
    }

    public record LoadCounts(long stagingCount, long parserRejectedCount) {
        public long totalCount() {
            return stagingCount + parserRejectedCount;
        }
    }

    public record ImportCounts(long totalCount, long acceptedCount, long rejectedCount) {
    }

    private record ValidationCounts(
            long stagingCount,
            long acceptedCount,
            long pendingCount,
            long inconsistentCount,
            long rejectedCount,
            long parserRejectedCount
    ) {

        private long totalCount() {
            return stagingCount + parserRejectedCount;
        }
    }

    private record CompletedBatch(
            String status,
            Long totalCount,
            Long acceptedCount,
            Long rejectedCount
    ) {
    }

    private RoadAddressImportException validationIncomplete(String message) {
        return new RoadAddressImportException(
                RoadAddressImportFailureCode.VALIDATION_INCOMPLETE,
                message
        );
    }

    private RoadAddressImportException duplicateSourceFile() {
        return new RoadAddressImportException(
                RoadAddressImportFailureCode.DUPLICATE_SOURCE_FILE,
                "이미 등록되었거나 처리 중인 도로명주소 CSV 파일입니다"
        );
    }

    private RoadAddressImportException duplicateSourceFile(Throwable cause) {
        return new RoadAddressImportException(
                RoadAddressImportFailureCode.DUPLICATE_SOURCE_FILE,
                "동일한 도로명주소 CSV 파일이 동시에 등록되었습니다",
                cause
        );
    }
}
