package com.address.address_system.address.importer.jibun.batch;

import com.address.address_system.address.importer.jibun.apply.JibunAddressApplyRepository;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;
import com.address.address_system.address.importer.jibun.model.JibunAddressImportRecord;
import com.address.address_system.address.importer.jibun.source.JibunAddressCsvFormat;
import com.address.address_system.address.importer.jibun.source.JibunAddressCsvLineMapper;
import com.address.address_system.address.importer.jibun.source.JibunAddressImportFileInspector;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JibunAddressImportProperties.class)
@ConditionalOnProperty(
        prefix = "address.import.jibun",
        name = "enabled",
        havingValue = "true"
)
public class JibunAddressImportJobConfiguration {

    private static final Logger log = LoggerFactory.getLogger(
            JibunAddressImportJobConfiguration.class
    );

    @Bean
    JibunAddressImportFileInspector jibunAddressImportFileInspector() {
        return new JibunAddressImportFileInspector();
    }

    @Bean
    JibunAddressImportBatchRepository jibunAddressImportBatchRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new JibunAddressImportBatchRepository(
                jdbcTemplate,
                new TransactionTemplate(transactionManager)
        );
    }

    @Bean
    JibunAddressStagingWriter jibunAddressStagingWriter(
            JdbcTemplate jdbcTemplate,
            JibunAddressImportProperties properties
    ) {
        return new JibunAddressStagingWriter(
                jdbcTemplate,
                properties.getMaxSkippedRows()
        );
    }

    @Bean
    JibunAddressApplyRepository jibunAddressApplyRepository(JdbcTemplate jdbcTemplate) {
        return new JibunAddressApplyRepository(jdbcTemplate);
    }

    @Bean
    @StepScope
    FlatFileItemReader<JibunAddressImportRecord> jibunAddressCsvReader(
            @Value("#{jobParameters['filePath']}") String filePath,
            @Value("#{jobParameters['batchId']}") String batchId
    ) {
        return new FlatFileItemReaderBuilder<JibunAddressImportRecord>()
                .name("jibunAddressCsvReader")
                .resource(new FileSystemResource(filePath))
                .encoding("UTF-8")
                .strict(true)
                .saveState(true)
                .linesToSkip(1)
                .skippedLinesCallback(JibunAddressCsvFormat::validateHeader)
                .lineMapper(new JibunAddressCsvLineMapper(UUID.fromString(batchId)))
                .build();
    }

    @Bean
    Step jibunAddressImportStep(
            JobRepository jobRepository,
            FlatFileItemReader<JibunAddressImportRecord> jibunAddressCsvReader,
            JibunAddressStagingWriter stagingWriter,
            JibunAddressImportProperties properties
    ) {
        return new StepBuilder("jibunAddressImportStep", jobRepository)
                .<JibunAddressImportRecord, JibunAddressImportRecord>chunk(
                        properties.getChunkSize()
                )
                .reader(jibunAddressCsvReader)
                .writer(stagingWriter)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Step jibunAddressApplyValidationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JibunAddressApplyRepository applyRepository
    ) {
        return new StepBuilder("jibunAddressApplyValidationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    ApplyContext context = applyContextOf(contribution);
                    applyRepository.validateApplication(
                            context.batchId(),
                            context.referenceDate()
                    );
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Step jibunAddressApplyStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JibunAddressApplyRepository applyRepository
    ) {
        return new StepBuilder("jibunAddressApplyStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    ApplyContext context = applyContextOf(contribution);
                    JibunAddressApplyRepository.ApplyCounts counts =
                            applyRepository.applyAndComplete(
                                    context.batchId(),
                                    context.referenceDate()
                            );
                    log.info(
                            "지번 운영 반영 완료. batchId={}, created={}, reactivated={}, retired={}, "
                                    + "retirementSkipped={}",
                            context.batchId(),
                            counts.createdCount(),
                            counts.reactivatedCount(),
                            counts.retiredCount(),
                            counts.retirementSkipped()
                    );
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Job jibunAddressImportJob(
            JobRepository jobRepository,
            @Qualifier("jibunAddressImportStep") Step importStep,
            @Qualifier("jibunAddressValidationPreparationStep") Step validationPreparationStep,
            @Qualifier("jibunAddressContentValidationStep") Step contentValidationStep,
            @Qualifier("jibunAddressRelationValidationStep") Step relationValidationStep,
            @Qualifier("jibunAddressApplyValidationStep") Step applyValidationStep,
            @Qualifier("jibunAddressApplyPreparationStep") Step applyPreparationStep,
            @Qualifier("jibunAddressApplyStep") Step applyStep
    ) {
        return new JobBuilder("jibunAddressImportJob", jobRepository)
                .start(importStep)
                .next(validationPreparationStep)
                .next(contentValidationStep)
                .next(relationValidationStep)
                .next(applyValidationStep)
                .next(applyPreparationStep)
                .next(applyStep)
                .build();
    }

    @Bean
    JibunAddressImportRunner jibunAddressImportRunner(
            JibunAddressImportProperties properties,
            JibunAddressImportFileInspector fileInspector,
            JibunAddressImportBatchRepository batchRepository,
            JobOperator jobOperator,
            Job jibunAddressImportJob
    ) {
        return new JibunAddressImportRunner(
                properties,
                fileInspector,
                batchRepository,
                jobOperator,
                jibunAddressImportJob
        );
    }

    private ApplyContext applyContextOf(StepContribution contribution) {
        String batchId = contribution.getStepExecution()
                .getJobParameters()
                .getString("batchId");
        String referenceDate = contribution.getStepExecution()
                .getJobParameters()
                .getString("referenceDate");
        if (batchId == null || referenceDate == null) {
            throw new IllegalStateException("지번 반영에 필요한 배치 식별값 또는 기준일이 없습니다");
        }
        return new ApplyContext(UUID.fromString(batchId), LocalDate.parse(referenceDate));
    }

    private record ApplyContext(UUID batchId, LocalDate referenceDate) {
    }

    static final class JibunAddressStagingWriter
            implements ItemWriter<JibunAddressImportRecord> {

        private static final String INSERT_STAGING_SQL = """
                INSERT INTO address.address_jibun_staging (
                    batch_id,
                    source_row_number,
                    mgmt_num,
                    b_dong_name,
                    ri_name,
                    jibun_main,
                    jibun_sub
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

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

        private final JdbcTemplate jdbcTemplate;
        private final int maxSkippedRows;

        JibunAddressStagingWriter(JdbcTemplate jdbcTemplate, int maxSkippedRows) {
            this.jdbcTemplate = jdbcTemplate;
            this.maxSkippedRows = maxSkippedRows;
        }

        @Override
        public void write(Chunk<? extends JibunAddressImportRecord> chunk) {
            ensureSingleBatch(chunk.getItems());

            List<JibunAddressImportRecord.Staging> stagingRows = chunk.getItems().stream()
                    .filter(JibunAddressImportRecord.Staging.class::isInstance)
                    .map(JibunAddressImportRecord.Staging.class::cast)
                    .toList();
            List<JibunAddressImportRecord.Rejected> rejectedRows = chunk.getItems().stream()
                    .filter(JibunAddressImportRecord.Rejected.class::isInstance)
                    .map(JibunAddressImportRecord.Rejected.class::cast)
                    .toList();

            enforceSkipLimit(rejectedRows);
            if (!stagingRows.isEmpty()) {
                jdbcTemplate.batchUpdate(
                        INSERT_STAGING_SQL,
                        stagingRows,
                        stagingRows.size(),
                        this::setStagingParameters
                );
            }
            if (!rejectedRows.isEmpty()) {
                jdbcTemplate.batchUpdate(
                        INSERT_REJECTION_SQL,
                        rejectedRows,
                        rejectedRows.size(),
                        this::setRejectionParameters
                );
            }
        }

        private void ensureSingleBatch(List<? extends JibunAddressImportRecord> records) {
            Set<UUID> batchIds = records.stream()
                    .map(JibunAddressImportRecord::batchId)
                    .collect(java.util.stream.Collectors.toSet());
            if (batchIds.size() > 1) {
                throw new IllegalStateException("하나의 청크에 서로 다른 지번 배치가 포함되었습니다");
            }
        }

        private void enforceSkipLimit(List<JibunAddressImportRecord.Rejected> rejectedRows) {
            if (rejectedRows.isEmpty()) {
                return;
            }
            UUID batchId = rejectedRows.get(0).batchId();
            Long existingCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM address.address_import_rejection WHERE batch_id = ?",
                    Long.class,
                    batchId
            );
            long totalRejected = (existingCount == null ? 0 : existingCount)
                    + rejectedRows.size();
            if (totalRejected > maxSkippedRows) {
                throw new JibunAddressImportException(
                        FailureCode.SKIP_LIMIT_EXCEEDED,
                        "지번 CSV 형식 오류 행이 허용 한도 " + maxSkippedRows + "건을 초과했습니다"
                );
            }
        }

        private void setStagingParameters(
                PreparedStatement statement,
                JibunAddressImportRecord.Staging row
        ) throws SQLException {
            statement.setObject(1, row.batchId());
            statement.setLong(2, row.sourceRowNumber());
            statement.setString(3, row.mgmtNum());
            statement.setString(4, row.bDongName());
            statement.setString(5, row.riName());
            statement.setString(6, row.jibunMain());
            statement.setString(7, row.jibunSub());
        }

        private void setRejectionParameters(
                PreparedStatement statement,
                JibunAddressImportRecord.Rejected row
        ) throws SQLException {
            statement.setObject(1, row.batchId());
            statement.setLong(2, row.sourceRowNumber());
            statement.setString(3, row.reasonCode());
            statement.setString(4, row.fieldName());
            statement.setString(5, row.rejectedValue());
            statement.setString(6, row.reasonDetail());
        }
    }
}
