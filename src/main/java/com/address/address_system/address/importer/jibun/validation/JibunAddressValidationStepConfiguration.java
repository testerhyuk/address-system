package com.address.address_system.address.importer.jibun.validation;

import com.address.address_system.address.importer.jibun.batch.JibunAddressImportBatchRepository;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportProperties;
import com.address.address_system.address.importer.jibun.model.JibunAddressImportRecord;
import com.address.address_system.address.importer.jibun.model.JibunAddressValidationResult;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "address.import.jibun",
        name = "enabled",
        havingValue = "true"
)
public class JibunAddressValidationStepConfiguration {

    @Bean
    JibunAddressContentValidator jibunAddressContentValidator() {
        return new JibunAddressContentValidator();
    }

    @Bean
    JibunAddressValidationWriter jibunAddressValidationWriter(JdbcTemplate jdbcTemplate) {
        return new JibunAddressValidationWriter(jdbcTemplate);
    }

    @Bean
    @StepScope
    JdbcPagingItemReader<JibunAddressImportRecord.Staging> jibunAddressStagingReader(
            DataSource dataSource,
            @Value("#{jobParameters['batchId']}") String batchId,
            JibunAddressImportProperties properties
    ) throws Exception {
        return new JdbcPagingItemReaderBuilder<JibunAddressImportRecord.Staging>()
                .name("jibunAddressStagingReader")
                .dataSource(dataSource)
                .selectClause("""
                        SELECT batch_id, source_row_number, mgmt_num, b_dong_name,
                               ri_name, jibun_main, jibun_sub
                        """)
                .fromClause("FROM address.address_jibun_staging")
                .whereClause("WHERE batch_id = :batchId AND processing_status = 'LOADED'")
                .parameterValues(Map.of("batchId", UUID.fromString(batchId)))
                .sortKeys(Map.of("source_row_number", Order.ASCENDING))
                .rowMapper((resultSet, rowNumber) -> new JibunAddressImportRecord.Staging(
                        resultSet.getObject("batch_id", UUID.class),
                        resultSet.getLong("source_row_number"),
                        resultSet.getString("mgmt_num"),
                        resultSet.getString("b_dong_name"),
                        resultSet.getString("ri_name"),
                        resultSet.getString("jibun_main"),
                        resultSet.getString("jibun_sub")
                ))
                .pageSize(properties.getChunkSize())
                .fetchSize(properties.getChunkSize())
                .saveState(true)
                .build();
    }

    @Bean
    Step jibunAddressValidationPreparationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JibunAddressImportBatchRepository batchRepository
    ) {
        return new StepBuilder("jibunAddressValidationPreparationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    batchRepository.markReadyForValidation(batchIdOf(contribution));
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Step jibunAddressContentValidationStep(
            JobRepository jobRepository,
            JdbcPagingItemReader<JibunAddressImportRecord.Staging> jibunAddressStagingReader,
            JibunAddressContentValidator validator,
            JibunAddressValidationWriter writer,
            JibunAddressImportProperties properties
    ) {
        return new StepBuilder("jibunAddressContentValidationStep", jobRepository)
                .<JibunAddressImportRecord.Staging, JibunAddressValidationResult>chunk(
                        properties.getChunkSize()
                )
                .reader(jibunAddressStagingReader)
                .processor(validator)
                .writer(writer)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Step jibunAddressRelationValidationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JibunAddressValidationWriter writer
    ) {
        return new StepBuilder("jibunAddressRelationValidationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    UUID batchId = batchIdOf(contribution);
                    writer.rejectDuplicateKeys(batchId);
                    writer.rejectMissingRoadAddresses(batchId);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Step jibunAddressApplyPreparationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JibunAddressImportBatchRepository batchRepository,
            JibunAddressImportProperties properties
    ) {
        return new StepBuilder("jibunAddressApplyPreparationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    batchRepository.markReadyForApply(
                            batchIdOf(contribution),
                            properties.getMaxSkippedRows()
                    );
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    private UUID batchIdOf(StepContribution contribution) {
        String value = contribution.getStepExecution()
                .getJobParameters()
                .getString("batchId");
        if (value == null) {
            throw new IllegalStateException("지번 배치 식별값이 없습니다");
        }
        return UUID.fromString(value);
    }

    static final class JibunAddressValidationWriter
            implements ItemWriter<JibunAddressValidationResult> {

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
                UPDATE address.address_jibun_staging
                SET processing_status = ?,
                    processed_at = CURRENT_TIMESTAMP
                WHERE batch_id = ?
                  AND source_row_number = ?
                  AND processing_status = 'LOADED'
                """;

        private final JdbcTemplate jdbcTemplate;

        JibunAddressValidationWriter(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public void write(Chunk<? extends JibunAddressValidationResult> chunk) {
            List<? extends JibunAddressValidationResult> results = chunk.getItems();
            ensureSingleBatch(results);

            List<Rejection> rejections = results.stream()
                    .flatMap(result -> result.violations().stream()
                            .map(violation -> new Rejection(result.row(), violation)))
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
                                JibunAddressValidationResult result = results.get(index);
                                statement.setString(
                                        1,
                                        result.isValid() ? "VALID" : "REJECTED"
                                );
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

        void rejectDuplicateKeys(UUID batchId) {
            jdbcTemplate.update(
                    """
                    WITH ranked AS (
                        SELECT source_row_number,
                               row_number() OVER (
                                   PARTITION BY
                                       btrim(mgmt_num),
                                       btrim(b_dong_name),
                                       COALESCE(NULLIF(btrim(ri_name), ''), ''),
                                       btrim(jibun_main)::integer,
                                       btrim(jibun_sub)::integer
                                   ORDER BY source_row_number
                               ) AS duplicate_rank
                        FROM address.address_jibun_staging
                        WHERE batch_id = ?
                          AND processing_status = 'VALID'
                    )
                    INSERT INTO address.address_import_rejection (
                        batch_id,
                        source_row_number,
                        reason_code,
                        field_name,
                        rejected_value,
                        reason_detail
                    )
                    SELECT ?,
                           ranked.source_row_number,
                           'DUPLICATE_JIBUN_KEY',
                           NULL,
                           NULL,
                           '같은 파일에서 먼저 나온 동일 지번 관계와 중복됩니다'
                    FROM ranked
                    WHERE ranked.duplicate_rank > 1
                    ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                        DO NOTHING
                    """,
                    batchId,
                    batchId
            );
            rejectRowsHavingReasons(batchId);
        }

        void rejectMissingRoadAddresses(UUID batchId) {
            jdbcTemplate.update(
                    """
                    INSERT INTO address.address_import_rejection (
                        batch_id,
                        source_row_number,
                        reason_code,
                        field_name,
                        rejected_value,
                        reason_detail
                    )
                    SELECT staging.batch_id,
                           staging.source_row_number,
                           'ROAD_ADDRESS_NOT_FOUND',
                           'mgmt_num',
                           left(staging.mgmt_num, 4000),
                           '활성 운영 도로명주소에 관리번호가 존재하지 않습니다'
                    FROM address.address_jibun_staging staging
                    WHERE staging.batch_id = ?
                      AND staging.processing_status = 'VALID'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM address.road_address road
                          WHERE road.mgmt_num = btrim(staging.mgmt_num)
                            AND road.status = 'ACTIVE'
                      )
                    ON CONFLICT (batch_id, source_row_number, reason_code, field_name)
                        DO NOTHING
                    """,
                    batchId
            );
            rejectRowsHavingReasons(batchId);
        }

        private void rejectRowsHavingReasons(UUID batchId) {
            jdbcTemplate.update(
                    """
                    UPDATE address.address_jibun_staging staging
                    SET processing_status = 'REJECTED',
                        processed_at = CURRENT_TIMESTAMP
                    WHERE staging.batch_id = ?
                      AND staging.processing_status = 'VALID'
                      AND EXISTS (
                          SELECT 1
                          FROM address.address_import_rejection rejection
                          WHERE rejection.batch_id = staging.batch_id
                            AND rejection.source_row_number = staging.source_row_number
                      )
                    """,
                    batchId
            );
        }

        private void setRejectionParameters(PreparedStatement statement, Rejection rejection)
                throws SQLException {
            statement.setObject(1, rejection.row().batchId());
            statement.setLong(2, rejection.row().sourceRowNumber());
            statement.setString(3, rejection.violation().reasonCode());
            statement.setString(4, rejection.violation().fieldName());
            statement.setString(5, truncate(
                    rejection.violation().rejectedValue(),
                    MAX_REJECTED_VALUE_LENGTH
            ));
            statement.setString(6, truncate(
                    rejection.violation().reasonDetail(),
                    MAX_REASON_DETAIL_LENGTH
            ));
        }

        private void ensureSingleBatch(
                List<? extends JibunAddressValidationResult> results
        ) {
            Set<UUID> batchIds = results.stream()
                    .map(result -> result.row().batchId())
                    .collect(java.util.stream.Collectors.toSet());
            if (batchIds.size() > 1) {
                throw new IllegalStateException(
                        "하나의 청크에 서로 다른 지번 검증 배치가 포함되었습니다"
                );
            }
        }

        private void verifyStatusUpdates(
                List<? extends JibunAddressValidationResult> results,
                int[] updateCounts
        ) {
            if (updateCounts.length != results.size()) {
                throw stateConflict("지번 검증 상태 변경 결과 수가 요청 수와 일치하지 않습니다");
            }
            for (int index = 0; index < updateCounts.length; index++) {
                int updateCount = updateCounts[index];
                if (updateCount != 1 && updateCount != Statement.SUCCESS_NO_INFO) {
                    JibunAddressImportRecord.Staging row = results.get(index).row();
                    throw stateConflict(
                            "LOADED 지번 행을 검증 상태로 변경하지 못했습니다. batchId="
                                    + row.batchId() + ", sourceRowNumber="
                                    + row.sourceRowNumber()
                    );
                }
            }
        }

        private JibunAddressImportException stateConflict(String message) {
            return new JibunAddressImportException(FailureCode.VALIDATION_INCOMPLETE, message);
        }

        private String truncate(String value, int maxLength) {
            if (value == null || value.length() <= maxLength) {
                return value;
            }
            return value.substring(0, maxLength);
        }

        private record Rejection(
                JibunAddressImportRecord.Staging row,
                JibunAddressValidationResult.Violation violation
        ) {
        }
    }
}
