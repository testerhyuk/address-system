package com.address.address_system.address.importer.jibun.batch;

import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;
import com.address.address_system.address.importer.jibun.source.JibunAddressImportFileInspector;
import com.address.address_system.address.importer.jibun.source.JibunAddressImportFileInspector.ImportFile;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public class JibunAddressImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JibunAddressImportRunner.class);

    private final JibunAddressImportProperties properties;
    private final JibunAddressImportFileInspector fileInspector;
    private final JibunAddressImportBatchRepository batchRepository;
    private final JobOperator jobOperator;
    private final Job importJob;

    public JibunAddressImportRunner(
            JibunAddressImportProperties properties,
            JibunAddressImportFileInspector fileInspector,
            JibunAddressImportBatchRepository batchRepository,
            JobOperator jobOperator,
            Job importJob
    ) {
        this.properties = properties;
        this.fileInspector = fileInspector;
        this.batchRepository = batchRepository;
        this.jobOperator = jobOperator;
        this.importJob = importJob;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        ImportFile file = fileInspector.inspect(properties.getFile());
        JibunAddressImportBatchRepository.RegisteredBatch batch =
                batchRepository.registerOrResume(file, properties.getReferenceDate());
        UUID batchId = batch.batchId();

        batchRepository.markLoading(batchId);
        log.info(
                "지번 FULL 적재를 시작합니다. batchId={}, referenceDate={}, fileName={}, "
                        + "fileSizeBytes={}",
                batchId,
                properties.getReferenceDate(),
                file.fileName(),
                file.fileSizeBytes()
        );

        try {
            JobExecution execution = jobOperator.start(importJob, createJobParameters(file, batchId));
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                throw new JibunAddressImportException(
                        failureCodeOfExecution(execution),
                        "지번 적재·검증·운영 반영 작업이 완료되지 않았습니다: "
                                + execution.getStatus()
                );
            }

            JibunAddressImportBatchRepository.ImportCounts counts =
                    batchRepository.getCompletedCounts(batchId);
            log.info(
                    "지번 FULL 적재가 완료되었습니다. batchId={}, totalRows={}, acceptedRows={}, "
                            + "rejectedRows={}",
                    batchId,
                    counts.totalCount(),
                    counts.acceptedCount(),
                    counts.rejectedCount()
            );
        }
        catch (Exception exception) {
            batchRepository.markFailed(batchId, failureCodeOf(exception));
            throw exception;
        }
    }

    private JobParameters createJobParameters(ImportFile file, UUID batchId) {
        return new JobParametersBuilder()
                .addString("fileSha256", file.sha256(), true)
                .addString("importMode", "FULL", true)
                .addString("referenceDate", properties.getReferenceDate().toString(), true)
                .addString("attemptId", UUID.randomUUID().toString(), true)
                .addString("filePath", file.path().toString(), false)
                .addString("batchId", batchId.toString(), false)
                .toJobParameters();
    }

    private FailureCode failureCodeOf(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof JibunAddressImportException importException) {
                return importException.getFailureCode();
            }
            current = current.getCause();
        }
        return FailureCode.IMPORT_JOB_FAILED;
    }

    private FailureCode failureCodeOfExecution(JobExecution execution) {
        return execution.getAllFailureExceptions().stream()
                .map(this::failureCodeOf)
                .filter(code -> code != FailureCode.IMPORT_JOB_FAILED)
                .findFirst()
                .orElse(FailureCode.IMPORT_JOB_FAILED);
    }
}
