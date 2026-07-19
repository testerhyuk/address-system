package com.address.address_system.address.importer.road.batch;

import com.address.address_system.address.importer.road.model.RoadAddressImportRecord;
import com.address.address_system.address.importer.road.source.RoadAddressCsvFormat;
import com.address.address_system.address.importer.road.source.RoadAddressCsvHeaderValidator;
import com.address.address_system.address.importer.road.source.RoadAddressCsvLineMapper;
import com.address.address_system.address.importer.road.source.RoadAddressImportFileInspector;

import java.util.UUID;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoadAddressImportProperties.class)
@ConditionalOnProperty(
        prefix = "address.import.road",
        name = "enabled",
        havingValue = "true"
)
public class RoadAddressImportJobConfiguration {

    @Bean
    RoadAddressImportFileInspector roadAddressImportFileInspector(
            RoadAddressCsvHeaderValidator headerValidator
    ) {
        return new RoadAddressImportFileInspector(headerValidator);
    }

    @Bean
    RoadAddressCsvHeaderValidator roadAddressCsvHeaderValidator() {
        return new RoadAddressCsvHeaderValidator();
    }

    @Bean
    RoadAddressImportBatchRepository roadAddressImportBatchRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new RoadAddressImportBatchRepository(
                jdbcTemplate,
                new TransactionTemplate(transactionManager)
        );
    }

    @Bean
    RoadAddressImportWriter roadAddressImportWriter(
            JdbcTemplate jdbcTemplate,
            RoadAddressImportProperties properties
    ) {
        return new RoadAddressImportWriter(jdbcTemplate, properties.getMaxSkippedRows());
    }

    @Bean
    @org.springframework.batch.core.configuration.annotation.StepScope
    FlatFileItemReader<RoadAddressImportRecord> roadAddressCsvReader(
            @Value("#{jobParameters['filePath']}") String filePath,
            @Value("#{jobParameters['batchId']}") String batchId,
            @Value("#{jobParameters['csvSchema']}") String csvSchema,
            RoadAddressCsvHeaderValidator headerValidator
    ) {
        RoadAddressCsvFormat.Schema schema = RoadAddressCsvFormat.Schema.valueOf(csvSchema);
        return new FlatFileItemReaderBuilder<RoadAddressImportRecord>()
                .name("roadAddressCsvReader")
                .resource(new FileSystemResource(filePath))
                .encoding("UTF-8")
                .strict(true)
                .saveState(true)
                .linesToSkip(1)
                .skippedLinesCallback(line -> headerValidator.validate(line, schema))
                .lineMapper(new RoadAddressCsvLineMapper(UUID.fromString(batchId), schema))
                .build();
    }

    @Bean
    Step roadAddressImportStep(
            JobRepository jobRepository,
            FlatFileItemReader<RoadAddressImportRecord> roadAddressCsvReader,
            RoadAddressImportWriter roadAddressImportWriter,
            RoadAddressImportProperties properties
    ) {
        return new StepBuilder("roadAddressImportStep", jobRepository)
                .<RoadAddressImportRecord, RoadAddressImportRecord>chunk(properties.getChunkSize())
                .reader(roadAddressCsvReader)
                .writer(roadAddressImportWriter)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Job roadAddressImportJob(
            JobRepository jobRepository,
            @Qualifier("roadAddressImportStep") Step roadAddressImportStep,
            @Qualifier("roadAddressValidationPreparationStep")
            Step roadAddressValidationPreparationStep,
            @Qualifier("roadAddressContentValidationStep") Step roadAddressContentValidationStep,
            @Qualifier("roadAddressDuplicateValidationStep") Step roadAddressDuplicateValidationStep,
            @Qualifier("roadAddressApplyValidationStep") Step roadAddressApplyValidationStep,
            @Qualifier("roadAddressApplyPreparationStep") Step roadAddressApplyPreparationStep,
            @Qualifier("roadAddressApplyStep") Step roadAddressApplyStep
    ) {
        return new JobBuilder("roadAddressImportJob", jobRepository)
                .start(roadAddressImportStep)
                .next(roadAddressValidationPreparationStep)
                .next(roadAddressContentValidationStep)
                .next(roadAddressDuplicateValidationStep)
                .next(roadAddressApplyValidationStep)
                .next(roadAddressApplyPreparationStep)
                .next(roadAddressApplyStep)
                .build();
    }

    @Bean
    RoadAddressImportRunner roadAddressImportRunner(
            RoadAddressImportProperties properties,
            RoadAddressImportFileInspector fileInspector,
            RoadAddressImportBatchRepository batchRepository,
            JobOperator jobOperator,
            Job roadAddressImportJob
    ) {
        return new RoadAddressImportRunner(
                properties,
                fileInspector,
                batchRepository,
                jobOperator,
                roadAddressImportJob
        );
    }
}
