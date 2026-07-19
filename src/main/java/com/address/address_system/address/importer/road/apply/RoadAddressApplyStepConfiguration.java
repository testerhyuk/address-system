package com.address.address_system.address.importer.road.apply;

import com.address.address_system.address.importer.road.model.RoadAddressImportMode;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
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
public class RoadAddressApplyStepConfiguration {

    @Bean
    RoadAddressApplyRepository roadAddressApplyRepository(JdbcTemplate jdbcTemplate) {
        return new RoadAddressApplyRepository(jdbcTemplate);
    }

    @Bean
    Step roadAddressApplyValidationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RoadAddressApplyRepository applyRepository
    ) {
        return new StepBuilder("roadAddressApplyValidationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    ApplyContext context = applyContextOf(contribution);
                    applyRepository.validateApplication(
                            context.batchId(),
                            context.importMode(),
                            context.referenceDate()
                    );
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    Step roadAddressApplyStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RoadAddressApplyRepository applyRepository
    ) {
        return new StepBuilder("roadAddressApplyStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    ApplyContext context = applyContextOf(contribution);
                    applyRepository.applyAndComplete(
                            context.batchId(),
                            context.importMode(),
                            context.referenceDate()
                    );
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    private ApplyContext applyContextOf(StepContribution contribution) {
        JobParameters parameters = contribution.getStepExecution()
                .getJobExecution()
                .getJobParameters();
        String batchId = parameters.getString("batchId");
        String importMode = parameters.getString("importMode");
        String referenceDate = parameters.getString("referenceDate");
        if (batchId == null || importMode == null || referenceDate == null) {
            throw new IllegalStateException("운영 주소 반영 작업의 필수 Job Parameter가 없습니다");
        }
        return new ApplyContext(
                UUID.fromString(batchId),
                RoadAddressImportMode.valueOf(importMode),
                LocalDate.parse(referenceDate)
        );
    }

    private record ApplyContext(
            UUID batchId,
            RoadAddressImportMode importMode,
            LocalDate referenceDate
    ) {
    }
}
