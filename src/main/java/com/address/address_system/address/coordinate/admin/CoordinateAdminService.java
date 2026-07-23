package com.address.address_system.address.coordinate.admin;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.address.address_system.address.coordinate.admin.CoordinateAdminRepository.CandidateView;
import com.address.address_system.address.coordinate.admin.CoordinateAdminRepository.CoordinateVersion;
import com.address.address_system.address.coordinate.admin.CoordinateAdminRepository.ActiveCoordinateVersion;
import com.address.address_system.address.coordinate.admin.CoordinateAdminRepository.ReviewCandidate;
import com.address.address_system.address.coordinate.admin.CoordinateAdminRepository.ProcessingFailureView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoordinateAdminService {

    private final CoordinateAdminRepository repository;
    private final Clock clock;

    public CoordinateAdminService(
            CoordinateAdminRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CandidateResult> findCandidates(CandidateStatus status, int limit) {
        return repository.findCandidates(status.name(), limit).stream()
                .map(CandidateResult::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProcessingFailureResult> findProcessingFailures(
            ProcessingFailureStatus status,
            int limit
    ) {
        return repository.findProcessingFailures(status.name(), limit).stream()
                .map(ProcessingFailureResult::from)
                .toList();
    }

    @Transactional
    public OperationResult approveCandidate(
            UUID candidateId,
            String actorClientId,
            String reason
    ) {
        AuditValues audit = auditValues(actorClientId, reason);
        ReviewCandidate candidate = repository.lockReviewCandidate(candidateId)
                .orElseThrow(() -> failure(
                        FailureCode.CANDIDATE_NOT_REVIEWABLE,
                        "검토 가능한 좌표 후보를 찾을 수 없습니다"
                ));
        lockTarget(candidate.targetId());

        Instant occurredAt = clock.instant();
        repository.retireActiveCoordinate(candidate.targetId(), occurredAt);
        long versionNumber = repository.nextVersionNumber(candidate.targetId());
        UUID coordinateId = UUID.randomUUID();
        repository.activateCandidate(
                coordinateId, candidate, versionNumber, occurredAt
        );
        repository.enableCoordinateServing(candidate.targetId());
        repository.recordAudit(
                UUID.randomUUID(),
                "CANDIDATE_APPROVED",
                audit.actorClientId(),
                audit.reason(),
                candidate.targetId(),
                candidate.candidateId(),
                coordinateId,
                null,
                null,
                occurredAt
        );
        return new OperationResult(
                "CANDIDATE_APPROVED", candidate.targetId(),
                coordinateId, versionNumber, null, occurredAt
        );
    }

    @Transactional
    public OperationResult rejectCandidate(
            UUID candidateId,
            String actorClientId,
            String reason
    ) {
        AuditValues audit = auditValues(actorClientId, reason);
        ReviewCandidate candidate = repository.lockReviewCandidate(candidateId)
                .orElseThrow(() -> failure(
                        FailureCode.CANDIDATE_NOT_REVIEWABLE,
                        "검토 가능한 좌표 후보를 찾을 수 없습니다"
                ));
        repository.rejectCandidate(candidateId);
        Instant occurredAt = clock.instant();
        repository.recordAudit(
                UUID.randomUUID(),
                "CANDIDATE_REJECTED",
                audit.actorClientId(),
                audit.reason(),
                candidate.targetId(),
                candidate.candidateId(),
                null,
                null,
                null,
                occurredAt
        );
        return new OperationResult(
                "CANDIDATE_REJECTED", candidate.targetId(),
                null, null, null, occurredAt
        );
    }

    @Transactional
    public OperationResult activateManualCoordinate(
            UUID targetId,
            BigDecimal latitude,
            BigDecimal longitude,
            String actorClientId,
            String reason
    ) {
        AuditValues audit = auditValues(actorClientId, reason);
        lockTarget(targetId);
        Instant occurredAt = clock.instant();
        repository.retireActiveCoordinate(targetId, occurredAt);
        long versionNumber = repository.nextVersionNumber(targetId);
        UUID coordinateId = UUID.randomUUID();
        repository.createManualCoordinate(
                coordinateId,
                targetId,
                versionNumber,
                latitude,
                longitude,
                occurredAt
        );
        repository.enableCoordinateServing(targetId);
        repository.recordAudit(
                UUID.randomUUID(),
                "MANUAL_COORDINATE_ACTIVATED",
                audit.actorClientId(),
                audit.reason(),
                targetId,
                null,
                coordinateId,
                null,
                null,
                occurredAt
        );
        return new OperationResult(
                "MANUAL_COORDINATE_ACTIVATED", targetId,
                coordinateId, versionNumber, null, occurredAt
        );
    }

    @Transactional
    public OperationResult restoreCoordinate(
            UUID targetId,
            long sourceVersionNumber,
            String actorClientId,
            String reason
    ) {
        AuditValues audit = auditValues(actorClientId, reason);
        lockTarget(targetId);
        CoordinateVersion source = repository.findVersion(
                targetId, sourceVersionNumber
        ).orElseThrow(() -> failure(
                FailureCode.COORDINATE_VERSION_NOT_FOUND,
                "복구할 좌표 버전을 찾을 수 없습니다"
        ));
        if ("ACTIVE".equals(source.status())) {
            throw failure(
                    FailureCode.COORDINATE_VERSION_ALREADY_ACTIVE,
                    "이미 활성 상태인 좌표 버전입니다"
            );
        }

        Instant occurredAt = clock.instant();
        repository.retireActiveCoordinate(targetId, occurredAt);
        long versionNumber = repository.nextVersionNumber(targetId);
        UUID coordinateId = UUID.randomUUID();
        repository.restoreCoordinate(
                coordinateId,
                targetId,
                versionNumber,
                source.coordinateId(),
                occurredAt
        );
        repository.enableCoordinateServing(targetId);
        repository.recordAudit(
                UUID.randomUUID(),
                "COORDINATE_RESTORED",
                audit.actorClientId(),
                audit.reason(),
                targetId,
                null,
                coordinateId,
                source.coordinateId(),
                null,
                occurredAt
        );
        return new OperationResult(
                "COORDINATE_RESTORED", targetId,
                coordinateId, versionNumber, null, occurredAt
        );
    }

    @Transactional
    public OperationResult excludeActiveCoordinate(
            UUID targetId,
            String actorClientId,
            String reason
    ) {
        AuditValues audit = auditValues(actorClientId, reason);
        lockTarget(targetId);
        ActiveCoordinateVersion active =
                repository.findActiveCoordinateVersion(targetId)
                        .orElseThrow(() -> failure(
                                FailureCode.ACTIVE_COORDINATE_NOT_FOUND,
                                "사용 중지할 운영 좌표를 찾을 수 없습니다"
                        ));

        Instant occurredAt = clock.instant();
        repository.excludeActiveCoordinate(targetId, occurredAt);
        repository.suspendCoordinateServing(targetId);
        repository.recordAudit(
                UUID.randomUUID(),
                "COORDINATE_EXCLUDED",
                audit.actorClientId(),
                audit.reason(),
                targetId,
                null,
                active.coordinateId(),
                null,
                null,
                occurredAt
        );
        return new OperationResult(
                "COORDINATE_EXCLUDED",
                targetId,
                active.coordinateId(),
                active.versionNumber(),
                null,
                occurredAt
        );
    }

    @Transactional
    public OperationResult requestReanalysis(
            UUID targetId,
            String actorClientId,
            String reason
    ) {
        AuditValues audit = auditValues(actorClientId, reason);
        lockTarget(targetId);
        Instant occurredAt = clock.instant();
        repository.resolveProcessingFailures(targetId, occurredAt);
        int affected = repository.requeueRetainedSamples(targetId);
        repository.recordAudit(
                UUID.randomUUID(),
                "REANALYSIS_REQUESTED",
                audit.actorClientId(),
                audit.reason(),
                targetId,
                null,
                null,
                null,
                affected,
                occurredAt
        );
        return new OperationResult(
                "REANALYSIS_REQUESTED", targetId,
                null, null, affected, occurredAt
        );
    }

    @Transactional
    public OperationResult retryFailedSamples(
            UUID targetId,
            String actorClientId,
            String reason
    ) {
        AuditValues audit = auditValues(actorClientId, reason);
        lockTarget(targetId);
        Instant occurredAt = clock.instant();
        repository.resolveProcessingFailures(targetId, occurredAt);
        int affected = repository.retryFailedSamples(targetId);
        repository.recordAudit(
                UUID.randomUUID(),
                "FAILED_SAMPLES_REQUEUED",
                audit.actorClientId(),
                audit.reason(),
                targetId,
                null,
                null,
                null,
                affected,
                occurredAt
        );
        return new OperationResult(
                "FAILED_SAMPLES_REQUEUED", targetId,
                null, null, affected, occurredAt
        );
    }

    private void lockTarget(UUID targetId) {
        if (!repository.lockActiveTarget(targetId)) {
            throw failure(
                    FailureCode.DELIVERY_TARGET_NOT_FOUND,
                    "활성 배송 대상을 찾을 수 없습니다"
            );
        }
    }

    private AuditValues auditValues(String actorClientId, String reason) {
        if (actorClientId == null || actorClientId.isBlank()
                || actorClientId.length() > 100
                || reason == null || reason.isBlank() || reason.length() > 500) {
            throw failure(FailureCode.INVALID_OPERATION_REQUEST,
                    "운영 요청의 실행 주체 또는 사유가 올바르지 않습니다");
        }
        return new AuditValues(actorClientId.trim(), reason.trim());
    }

    private CoordinateAdminException failure(FailureCode code, String message) {
        return new CoordinateAdminException(code, message);
    }

    private record AuditValues(String actorClientId, String reason) {
    }

    public record CandidateResult(
            UUID candidateId,
            UUID deliveryTargetId,
            UUID runId,
            BigDecimal latitude,
            BigDecimal longitude,
            int sampleCount,
            BigDecimal radiusMeters,
            CandidateStatus status,
            BigDecimal qualityScore,
            BigDecimal dominanceRatio,
            BigDecimal outlierRatio,
            String reasonCode,
            Instant evaluatedAt,
            Long activeVersionNumber
    ) {
        static CandidateResult from(CandidateView view) {
            return new CandidateResult(
                    view.candidateId(),
                    view.targetId(),
                    view.runId(),
                    view.latitude(),
                    view.longitude(),
                    view.sampleCount(),
                    view.radiusMeters(),
                    CandidateStatus.valueOf(view.status()),
                    view.qualityScore(),
                    view.dominanceRatio(),
                    view.outlierRatio(),
                    view.reasonCode(),
                    view.evaluatedAt(),
                    view.activeVersionNumber()
            );
        }
    }

    public record OperationResult(
            String action,
            UUID deliveryTargetId,
            UUID coordinateId,
            Long versionNumber,
            Integer affectedSampleCount,
            Instant occurredAt
    ) {
    }

    public record ProcessingFailureResult(
            UUID failureId,
            String stage,
            UUID deliveryTargetId,
            UUID candidateId,
            ProcessingFailureStatus status,
            int attemptCount,
            String failureCode,
            Instant firstFailedAt,
            Instant lastFailedAt,
            Instant nextRetryAt
    ) {
        static ProcessingFailureResult from(ProcessingFailureView view) {
            return new ProcessingFailureResult(
                    view.failureId(),
                    view.stage(),
                    view.targetId(),
                    view.candidateId(),
                    ProcessingFailureStatus.valueOf(view.status()),
                    view.attemptCount(),
                    view.failureCode(),
                    view.firstFailedAt(),
                    view.lastFailedAt(),
                    view.nextRetryAt()
            );
        }
    }

    public enum CandidateStatus {
        GENERATED,
        REVIEW_REQUIRED,
        APPROVED,
        REJECTED,
        PROMOTED,
        CONFIRMED,
        INVALIDATED
    }

    public enum ProcessingFailureStatus {
        RETRY_SCHEDULED,
        EXHAUSTED,
        RESOLVED
    }

    public enum FailureCode {
        INVALID_OPERATION_REQUEST,
        DELIVERY_TARGET_NOT_FOUND,
        CANDIDATE_NOT_REVIEWABLE,
        COORDINATE_VERSION_NOT_FOUND,
        COORDINATE_VERSION_ALREADY_ACTIVE,
        ACTIVE_COORDINATE_NOT_FOUND
    }

    public static class CoordinateAdminException extends RuntimeException {
        private final FailureCode failureCode;

        public CoordinateAdminException(FailureCode failureCode, String message) {
            super(message);
            this.failureCode = failureCode;
        }

        public FailureCode getFailureCode() {
            return failureCode;
        }
    }
}
