package com.address.address_system.address.importer.road;

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

public class RoadAddressImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoadAddressImportRunner.class);

    private final RoadAddressImportProperties properties;
    private final RoadAddressImportFileInspector fileInspector;
    private final RoadAddressImportBatchRepository batchRepository;
    private final JobOperator jobOperator;
    private final Job roadAddressImportJob;

    public RoadAddressImportRunner(
            RoadAddressImportProperties properties,
            RoadAddressImportFileInspector fileInspector,
            RoadAddressImportBatchRepository batchRepository,
            JobOperator jobOperator,
            Job roadAddressImportJob
    ) {
        this.properties = properties;
        this.fileInspector = fileInspector;
        this.batchRepository = batchRepository;
        this.jobOperator = jobOperator;
        this.roadAddressImportJob = roadAddressImportJob;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        RoadAddressImportFile file = fileInspector.inspect(properties.getFile());
        validateSourceMode(file);
        RoadAddressImportBatchRepository.RegisteredBatch batch =
                batchRepository.registerOrResume(
                        file,
                        properties.getMode(),
                        properties.getReferenceDate()
                );
        UUID batchId = batch.batchId();

        batchRepository.markLoading(batchId);
        log.info(
                "도로명주소 적재·검증·운영 반영을 시작합니다. "
                        + "batchId={}, importMode={}, referenceDate={}, fileName={}, fileSizeBytes={}",
                batchId,
                properties.getMode(),
                properties.getReferenceDate(),
                file.fileName(),
                file.fileSizeBytes()
        );

        try {
            JobExecution execution = jobOperator.start(
                    roadAddressImportJob,
                    createJobParameters(file, batchId)
            );
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                throw new RoadAddressImportException(
                        failureCodeOfExecution(execution),
                        "도로명주소 적재·검증·운영 반영 작업이 완료되지 않았습니다: "
                                + execution.getStatus()
                );
            }

            RoadAddressImportBatchRepository.ImportCounts counts =
                    batchRepository.getCompletedCounts(batchId);
            log.info(
                    "도로명주소 적재·검증·운영 반영을 완료했습니다. "
                            + "batchId={}, totalRows={}, acceptedRows={}, rejectedRows={}",
                    batchId,
                    counts.totalCount(),
                    counts.acceptedCount(),
                    counts.rejectedCount()
            );
        }
        catch (Exception exception) {
            RoadAddressImportFailureCode failureCode = failureCodeOf(exception);
            batchRepository.markFailed(batchId, failureCode);
            throw exception;
        }
    }

    private void validateSourceMode(RoadAddressImportFile file) {
        if (file.schema() == RoadAddressCsvFormat.Schema.SNAPSHOT
                && properties.getMode() != RoadAddressImportMode.FULL) {
            throw new RoadAddressImportException(
                    RoadAddressImportFailureCode.UNSUPPORTED_SOURCE_MODE,
                    "10컬럼 스냅샷 CSV는 FULL 적재에서만 사용할 수 있습니다"
            );
        }
    }

    private JobParameters createJobParameters(RoadAddressImportFile file, UUID batchId) {
        return new JobParametersBuilder()
                .addString("fileSha256", file.sha256(), true)
                .addString("importMode", properties.getMode().name(), true)
                .addString("referenceDate", properties.getReferenceDate().toString(), true)
                .addString("csvSchema", file.schema().name(), true)
                .addString("filePath", file.path().toString(), false)
                .addString("batchId", batchId.toString(), false)
                .toJobParameters();
    }

    private RoadAddressImportFailureCode failureCodeOf(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RoadAddressImportException importException) {
                return importException.getFailureCode();
            }
            current = current.getCause();
        }
        return RoadAddressImportFailureCode.IMPORT_JOB_FAILED;
    }

    private RoadAddressImportFailureCode failureCodeOfExecution(JobExecution execution) {
        return execution.getAllFailureExceptions().stream()
                .map(this::failureCodeOf)
                .filter(code -> code != RoadAddressImportFailureCode.IMPORT_JOB_FAILED)
                .findFirst()
                .orElse(RoadAddressImportFailureCode.IMPORT_JOB_FAILED);
    }
}
