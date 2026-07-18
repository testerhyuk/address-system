package com.address.address_system.address.importer.road;

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

    public RegisteredBatch registerOrResume(RoadAddressImportFile file) {
        try {
            return transactionTemplate.execute(status -> {
                List<RegisteredBatch> existing = jdbcTemplate.query(
                        """
                        SELECT batch_id, status
                        FROM address.address_import_batch
                        WHERE source_type = 'ROAD'
                          AND source_file_sha256 = ?
                        """,
                        (resultSet, rowNumber) -> new RegisteredBatch(
                                resultSet.getObject("batch_id", UUID.class),
                                resultSet.getString("status")
                        ),
                        file.sha256()
                );

                if (!existing.isEmpty()) {
                    RegisteredBatch batch = existing.get(0);
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
                            file_size_bytes,
                            status
                        ) VALUES (?, 'ROAD', ?, ?, ?, 'REGISTERED')
                        """,
                        batchId,
                        file.fileName(),
                        file.sha256(),
                        file.fileSizeBytes()
                );
                return new RegisteredBatch(batchId, "REGISTERED");
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

    public ImportCounts markReadyForValidation(UUID batchId) {
        ImportCounts counts = jdbcTemplate.queryForObject(
                """
                SELECT
                    (SELECT count(*) FROM address.address_road_staging WHERE batch_id = ?) AS staging_count,
                    (SELECT count(*) FROM address.address_import_rejection WHERE batch_id = ?) AS rejection_count
                """,
                (resultSet, rowNumber) -> new ImportCounts(
                        resultSet.getLong("staging_count"),
                        resultSet.getLong("rejection_count")
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
                counts.rejectedCount(),
                batchId
        );
        if (updated != 1) {
            throw new IllegalStateException("적재 배치를 VALIDATING 상태로 변경하지 못했습니다: " + batchId);
        }
        return counts;
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

    public record RegisteredBatch(UUID batchId, String status) {
    }

    public record ImportCounts(long stagingCount, long rejectedCount) {
        public long totalCount() {
            return stagingCount + rejectedCount;
        }
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
