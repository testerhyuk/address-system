package com.address.address_system.address.importer.jibun.batch;

import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;
import com.address.address_system.address.importer.jibun.source.JibunAddressImportFileInspector.ImportFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class JibunAddressImportBatchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JibunAddressImportBatchRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public RegisteredBatch registerOrResume(ImportFile file, LocalDate referenceDate) {
        try {
            return transactionTemplate.execute(status -> {
                List<RegisteredBatch> existing = jdbcTemplate.query(
                        """
                        SELECT batch_id, status, source_reference_date
                        FROM address.address_import_batch
                        WHERE source_type = 'JIBUN'
                          AND source_file_sha256 = ?
                        """,
                        (resultSet, rowNumber) -> new RegisteredBatch(
                                resultSet.getObject("batch_id", UUID.class),
                                resultSet.getString("status"),
                                resultSet.getObject("source_reference_date", LocalDate.class)
                        ),
                        file.sha256()
                );

                if (!existing.isEmpty()) {
                    RegisteredBatch batch = existing.get(0);
                    if (!referenceDate.equals(batch.referenceDate())) {
                        throw duplicateSourceFile();
                    }
                    if ("FAILED".equals(batch.status())) {
                        resetFailedBatch(batch.batchId());
                        return batch;
                    }
                    if ("REGISTERED".equals(batch.status())) {
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
                        ) VALUES (?, 'JIBUN', ?, ?, ?, ?, 'FULL', 'REGISTERED')
                        """,
                        batchId,
                        file.fileName(),
                        file.sha256(),
                        referenceDate,
                        file.fileSizeBytes()
                );
                return new RegisteredBatch(batchId, "REGISTERED", referenceDate);
            });
        }
        catch (DuplicateKeyException exception) {
            throw duplicateSourceFile(exception);
        }
    }

    private void resetFailedBatch(UUID batchId) {
        jdbcTemplate.update(
                "DELETE FROM address.address_import_rejection WHERE batch_id = ?",
                batchId
        );
        jdbcTemplate.update(
                "DELETE FROM address.address_jibun_staging WHERE batch_id = ?",
                batchId
        );
        jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'REGISTERED',
                    total_row_count = NULL,
                    accepted_row_count = NULL,
                    rejected_row_count = NULL,
                    started_at = NULL,
                    completed_at = NULL,
                    failure_reason_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND status = 'FAILED'
                """,
                batchId
        );
    }

    public void markLoading(UUID batchId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'LOADING',
                    started_at = CURRENT_TIMESTAMP,
                    total_row_count = NULL,
                    accepted_row_count = NULL,
                    rejected_row_count = NULL,
                    completed_at = NULL,
                    failure_reason_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND source_type = 'JIBUN'
                  AND import_mode = 'FULL'
                  AND status = 'REGISTERED'
                """,
                batchId
        );
        if (updated != 1) {
            throw new IllegalStateException("지번 배치를 LOADING 상태로 변경하지 못했습니다: " + batchId);
        }
    }

    public LoadCounts markReadyForValidation(UUID batchId) {
        LoadCounts counts = jdbcTemplate.queryForObject(
                """
                SELECT
                    (SELECT count(*)
                     FROM address.address_jibun_staging
                     WHERE batch_id = ?) AS staging_count,
                    (SELECT count(DISTINCT rejection.source_row_number)
                     FROM address.address_import_rejection rejection
                     WHERE rejection.batch_id = ?
                       AND NOT EXISTS (
                           SELECT 1
                           FROM address.address_jibun_staging staging
                           WHERE staging.batch_id = rejection.batch_id
                             AND staging.source_row_number = rejection.source_row_number
                       )) AS parser_rejection_count
                """,
                (resultSet, rowNumber) -> new LoadCounts(
                        resultSet.getLong("staging_count"),
                        resultSet.getLong("parser_rejection_count")
                ),
                batchId,
                batchId
        );
        if (counts == null) {
            throw validationIncomplete("지번 적재 건수를 조회하지 못했습니다: " + batchId);
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'VALIDATING',
                    total_row_count = ?,
                    accepted_row_count = NULL,
                    rejected_row_count = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND source_type = 'JIBUN'
                  AND status = 'LOADING'
                """,
                counts.totalCount(),
                counts.parserRejectedCount(),
                batchId
        );
        if (updated != 1) {
            throw validationIncomplete("지번 배치를 VALIDATING 상태로 변경하지 못했습니다: " + batchId);
        }
        return counts;
    }

    public ImportCounts markReadyForApply(UUID batchId, int maxSkippedRows) {
        ValidationCounts counts = jdbcTemplate.queryForObject(
                """
                SELECT
                    (SELECT count(*)
                     FROM address.address_jibun_staging
                     WHERE batch_id = ?) AS staging_count,
                    (SELECT count(*)
                     FROM address.address_jibun_staging
                     WHERE batch_id = ?
                       AND processing_status = 'VALID') AS accepted_count,
                    (SELECT count(*)
                     FROM address.address_jibun_staging
                     WHERE batch_id = ?
                       AND processing_status = 'LOADED') AS pending_count,
                    (SELECT count(DISTINCT source_row_number)
                     FROM address.address_import_rejection
                     WHERE batch_id = ?) AS rejected_count,
                    (SELECT count(DISTINCT rejection.source_row_number)
                     FROM address.address_import_rejection rejection
                     WHERE rejection.batch_id = ?
                       AND NOT EXISTS (
                           SELECT 1
                           FROM address.address_jibun_staging staging
                           WHERE staging.batch_id = rejection.batch_id
                             AND staging.source_row_number = rejection.source_row_number
                       )) AS parser_rejection_count,
                    (SELECT count(*)
                     FROM address.address_jibun_staging staging
                     WHERE staging.batch_id = ?
                       AND (
                           (staging.processing_status = 'REJECTED' AND NOT EXISTS (
                               SELECT 1
                               FROM address.address_import_rejection rejection
                               WHERE rejection.batch_id = staging.batch_id
                                 AND rejection.source_row_number = staging.source_row_number
                           ))
                           OR (staging.processing_status = 'VALID' AND EXISTS (
                               SELECT 1
                               FROM address.address_import_rejection rejection
                               WHERE rejection.batch_id = staging.batch_id
                                 AND rejection.source_row_number = staging.source_row_number
                           ))
                       )) AS inconsistent_count
                """,
                (resultSet, rowNumber) -> new ValidationCounts(
                        resultSet.getLong("staging_count"),
                        resultSet.getLong("accepted_count"),
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("rejected_count"),
                        resultSet.getLong("parser_rejection_count"),
                        resultSet.getLong("inconsistent_count")
                ),
                batchId,
                batchId,
                batchId,
                batchId,
                batchId,
                batchId
        );
        if (counts == null) {
            throw validationIncomplete("지번 검증 건수를 조회하지 못했습니다: " + batchId);
        }

        ImportCounts result = new ImportCounts(
                counts.stagingCount() + counts.parserRejectionCount(),
                counts.acceptedCount(),
                counts.rejectedCount()
        );
        if (counts.pendingCount() != 0) {
            throw validationIncomplete("검증되지 않은 지번 행이 남아 있습니다: " + counts.pendingCount());
        }
        if (counts.inconsistentCount() != 0) {
            throw validationIncomplete("지번 검증 상태와 거부 사유가 일치하지 않습니다");
        }
        if (result.acceptedCount() + result.rejectedCount() != result.totalCount()) {
            throw validationIncomplete("지번 전체·정상·거부 건수 합계가 일치하지 않습니다");
        }
        if (result.rejectedCount() > maxSkippedRows) {
            throw new JibunAddressImportException(
                    FailureCode.SKIP_LIMIT_EXCEEDED,
                    "지번 거부 행이 허용 한도 " + maxSkippedRows + "건을 초과했습니다"
            );
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'APPLYING',
                    total_row_count = ?,
                    accepted_row_count = ?,
                    rejected_row_count = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND source_type = 'JIBUN'
                  AND status = 'VALIDATING'
                """,
                result.totalCount(),
                result.acceptedCount(),
                result.rejectedCount(),
                batchId
        );
        if (updated != 1) {
            throw validationIncomplete("지번 배치를 APPLYING 상태로 변경하지 못했습니다: " + batchId);
        }
        return result;
    }

    public ImportCounts getCompletedCounts(UUID batchId) {
        CompletedBatch batch = jdbcTemplate.queryForObject(
                """
                SELECT status, total_row_count, accepted_row_count, rejected_row_count
                FROM address.address_import_batch
                WHERE batch_id = ?
                  AND source_type = 'JIBUN'
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
            throw validationIncomplete("완료된 지번 배치 건수를 조회하지 못했습니다: " + batchId);
        }
        return new ImportCounts(
                batch.totalCount(),
                batch.acceptedCount(),
                batch.rejectedCount()
        );
    }

    public void markFailed(UUID batchId, FailureCode failureCode) {
        jdbcTemplate.update(
                """
                UPDATE address.address_import_batch
                SET status = 'FAILED',
                    completed_at = CURRENT_TIMESTAMP,
                    failure_reason_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND source_type = 'JIBUN'
                  AND status <> 'COMPLETED'
                """,
                failureCode.name(),
                batchId
        );
    }

    public record RegisteredBatch(UUID batchId, String status, LocalDate referenceDate) {
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
            long rejectedCount,
            long parserRejectionCount,
            long inconsistentCount
    ) {
    }

    private record CompletedBatch(
            String status,
            Long totalCount,
            Long acceptedCount,
            Long rejectedCount
    ) {
    }

    private JibunAddressImportException validationIncomplete(String message) {
        return new JibunAddressImportException(FailureCode.VALIDATION_INCOMPLETE, message);
    }

    private JibunAddressImportException duplicateSourceFile() {
        return new JibunAddressImportException(
                FailureCode.DUPLICATE_SOURCE_FILE,
                "이미 처리되었거나 처리 중인 동일한 지번 원본 파일입니다"
        );
    }

    private JibunAddressImportException duplicateSourceFile(Throwable cause) {
        return new JibunAddressImportException(
                FailureCode.DUPLICATE_SOURCE_FILE,
                "동일한 지번 원본 파일을 중복 등록할 수 없습니다",
                cause
        );
    }
}
