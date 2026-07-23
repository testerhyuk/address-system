package com.address.address_system.address.coordinate.failure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.coordinate.analysis.CoordinateAnalysisProperties;
import com.address.address_system.address.coordinate.failure.CoordinateProcessingFailureRepository.FailureState;
import com.address.address_system.address.coordinate.quality.CoordinateQualityProperties;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoordinateProcessingFailureService {

    private final CoordinateProcessingFailureRepository repository;
    private final CoordinateAnalysisProperties analysisProperties;
    private final CoordinateQualityProperties qualityProperties;
    private final Clock clock;

    public CoordinateProcessingFailureService(
            CoordinateProcessingFailureRepository repository,
            CoordinateAnalysisProperties analysisProperties,
            CoordinateQualityProperties qualityProperties,
            Clock clock
    ) {
        this.repository = repository;
        this.analysisProperties = analysisProperties;
        this.qualityProperties = qualityProperties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAnalysisFailure(UUID targetId, RuntimeException exception) {
        record(
                Stage.ANALYSIS,
                targetId,
                null,
                exception,
                analysisProperties.maxAttempts(),
                analysisProperties.initialRetryDelay(),
                analysisProperties.maxRetryDelay()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordQualityFailure(UUID candidateId, RuntimeException exception) {
        repository.findCandidateTarget(candidateId).ifPresent(targetId -> record(
                Stage.QUALITY,
                targetId,
                candidateId,
                exception,
                qualityProperties.maxAttempts(),
                qualityProperties.initialRetryDelay(),
                qualityProperties.maxRetryDelay()
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveAnalysis(UUID targetId) {
        repository.resolve(
                Stage.ANALYSIS.name(), targetId, null, clock.instant()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveQuality(UUID candidateId) {
        repository.findCandidateTarget(candidateId).ifPresent(targetId ->
                repository.resolve(
                        Stage.QUALITY.name(), targetId, candidateId, clock.instant()
                )
        );
    }

    private void record(
            Stage stage,
            UUID targetId,
            UUID candidateId,
            RuntimeException exception,
            int maxAttempts,
            Duration initialDelay,
            Duration maxDelay
    ) {
        Instant failedAt = clock.instant();
        Optional<FailureState> current = repository.lock(
                stage.name(), targetId, candidateId
        );
        int attemptCount = current
                .filter(state -> !"RESOLVED".equals(state.status()))
                .map(state -> state.attemptCount() + 1)
                .orElse(1);
        boolean exhausted = attemptCount >= maxAttempts;
        String status = exhausted ? "EXHAUSTED" : "RETRY_SCHEDULED";
        Instant nextRetryAt = exhausted
                ? null
                : failedAt.plus(retryDelay(attemptCount, initialDelay, maxDelay));
        String failureCode = exception.getClass().getSimpleName();
        if (failureCode.length() > 100) {
            failureCode = failureCode.substring(0, 100);
        }

        if (current.isEmpty()) {
            repository.insert(
                    UUID.randomUUID(), stage.name(), targetId, candidateId,
                    status, attemptCount, failureCode, failedAt, nextRetryAt
            );
        }
        else if ("RESOLVED".equals(current.get().status())) {
            repository.restartResolved(
                    current.get().failureId(), status, failureCode,
                    failedAt, nextRetryAt
            );
        }
        else {
            repository.updateFailure(
                    current.get().failureId(), status, attemptCount,
                    failureCode, failedAt, nextRetryAt
            );
        }
    }

    private Duration retryDelay(
            int attemptCount,
            Duration initialDelay,
            Duration maximumDelay
    ) {
        Duration delay = initialDelay;
        for (int attempt = 1; attempt < attemptCount; attempt++) {
            if (delay.compareTo(maximumDelay.dividedBy(2)) > 0) {
                return maximumDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximumDelay) > 0 ? maximumDelay : delay;
    }

    private enum Stage {
        ANALYSIS,
        QUALITY
    }
}
