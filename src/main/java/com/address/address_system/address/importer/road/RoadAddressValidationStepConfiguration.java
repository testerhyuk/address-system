package com.address.address_system.address.importer.road;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
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
        prefix = "address.import.road",
        name = "enabled",
        havingValue = "true"
)
public class RoadAddressValidationStepConfiguration {

    @Bean
    @org.springframework.batch.core.configuration.annotation.StepScope
    RoadAddressContentValidator roadAddressContentValidator(
            @Value("#{jobParameters['importMode']}") String importMode
    ) {
        return new RoadAddressContentValidator(RoadAddressImportMode.valueOf(importMode));
    }

    @Bean
    RoadAddressValidationWriter roadAddressValidationWriter(JdbcTemplate jdbcTemplate) {
        return new RoadAddressValidationWriter(jdbcTemplate);
    }

    @Bean
    @org.springframework.batch.core.configuration.annotation.StepScope
    JdbcPagingItemReader<RoadAddressStagingRow> roadAddressStagingValidationReader(
            @Value("#{jobParameters['batchId']}") String batchId,
            DataSource dataSource,
            RoadAddressImportProperties properties
    ) throws Exception {
        return new JdbcPagingItemReaderBuilder<RoadAddressStagingRow>()
                .name("roadAddressStagingValidationReader")
                .dataSource(dataSource)
                .selectClause("""
                        SELECT
                            batch_id,
                            source_row_number,
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
                            effective_date,
                            apartment_flag,
                            movement_reason_code,
                            build_nm_official,
                            build_nm_sgg
                        """)
                .fromClause("FROM address.address_road_staging")
                .whereClause("WHERE batch_id = :batchId AND processing_status = 'LOADED'")
                .sortKeys(Map.of("source_row_number", Order.ASCENDING))
                .parameterValues(Map.of("batchId", UUID.fromString(batchId)))
                .pageSize(properties.getChunkSize())
                .fetchSize(properties.getChunkSize())
                .saveState(true)
                .rowMapper((resultSet, rowNumber) -> new RoadAddressStagingRow(
                        resultSet.getObject("batch_id", UUID.class),
                        resultSet.getLong("source_row_number"),
                        resultSet.getString("mgmt_num"),
                        resultSet.getString("legal_area_code"),
                        resultSet.getString("legal_dong_code"),
                        resultSet.getString("sido"),
                        resultSet.getString("sigungu"),
                        resultSet.getString("b_dong_name"),
                        resultSet.getString("road_code"),
                        resultSet.getString("road_name"),
                        resultSet.getString("underground_flag"),
                        resultSet.getString("build_main"),
                        resultSet.getString("build_sub"),
                        resultSet.getString("zip_code"),
                        resultSet.getString("effective_date"),
                        resultSet.getString("apartment_flag"),
                        resultSet.getString("movement_reason_code"),
                        resultSet.getString("build_nm_official"),
                        resultSet.getString("build_nm_sgg")
                ))
                .build();
    }

    @Bean
    Step roadAddressValidationPreparationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RoadAddressImportBatchRepository batchRepository
    ) {
        return new StepBuilder("roadAddressValidationPreparationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    batchRepository.markReadyForValidation(batchIdOf(contribution));
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Step roadAddressContentValidationStep(
            JobRepository jobRepository,
            JdbcPagingItemReader<RoadAddressStagingRow> roadAddressStagingValidationReader,
            RoadAddressContentValidator roadAddressContentValidator,
            RoadAddressValidationWriter roadAddressValidationWriter,
            RoadAddressImportProperties properties
    ) {
        return new StepBuilder("roadAddressContentValidationStep", jobRepository)
                .<RoadAddressStagingRow, RoadAddressValidationResult>chunk(properties.getChunkSize())
                .reader(roadAddressStagingValidationReader)
                .processor(roadAddressContentValidator)
                .writer(roadAddressValidationWriter)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Step roadAddressDuplicateValidationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RoadAddressValidationWriter validationWriter
    ) {
        return new StepBuilder("roadAddressDuplicateValidationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    validationWriter.rejectDuplicateAddressKeys(batchIdOf(contribution));
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Step roadAddressApplyPreparationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RoadAddressImportBatchRepository batchRepository,
            RoadAddressImportProperties properties
    ) {
        return new StepBuilder("roadAddressApplyPreparationStep", jobRepository)
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
        String batchId = contribution.getStepExecution()
                .getJobExecution()
                .getJobParameters()
                .getString("batchId");
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalStateException("도로명주소 적재 작업의 batchId 파라미터가 없습니다");
        }
        return UUID.fromString(batchId);
    }
}
