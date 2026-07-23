package com.address.address_system.address.coordinate.quality;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.coordinate.quality.CoordinateQualityPolicy.Evaluation;
import com.address.address_system.address.coordinate.quality.CoordinateQualityPolicy.Evidence;
import com.address.address_system.address.coordinate.quality.CoordinateQualityRepository.ActiveCoordinate;
import com.address.address_system.address.coordinate.quality.CoordinateQualityRepository.CandidateEvidence;
import com.address.address_system.address.coordinate.quality.CoordinateQualityRepository.TargetState;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoordinateQualityService {

    private final CoordinateQualityRepository repository;
    private final CoordinateQualityPolicy policy;
    private final CoordinateQualityProperties properties;
    private final Clock clock;

    public CoordinateQualityService(
            CoordinateQualityRepository repository,
            CoordinateQualityPolicy policy,
            CoordinateQualityProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.policy = policy;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public boolean evaluate(UUID candidateId) {
        Optional<CandidateEvidence> locked =
                repository.lockGeneratedCandidate(candidateId);
        if (locked.isEmpty()) {
            return false;
        }
        CandidateEvidence candidate = locked.get();
        Optional<TargetState> targetState =
                repository.lockActiveTarget(candidate.targetId());
        if (targetState.isEmpty()) {
            return false;
        }

        Optional<ActiveCoordinate> active = repository.findActiveCoordinate(
                candidate.targetId(), candidate.candidateId()
        );
        BigDecimal distance = active.map(ActiveCoordinate::distanceMeters).orElse(null);
        Evaluation evaluation = policy.evaluate(new Evidence(
                candidate.candidateSampleCount(),
                candidate.totalSampleCount(),
                candidate.outlierCount(),
                candidate.radiusMeters(),
                candidate.topCandidate(),
                candidate.secondSampleCount(),
                distance,
                targetState.orElseThrow().automaticPromotionAllowed()
        ));

        Instant evaluatedAt = clock.instant();
        UUID coordinateId = switch (evaluation.decision()) {
            case PROMOTED -> promote(candidate, evaluation, evaluatedAt);
            case CONFIRMED -> confirm(candidate, active.orElseThrow(), evaluation);
            case REVIEW_REQUIRED -> {
                repository.updateCandidateStatus(candidate.candidateId(),
                        "REVIEW_REQUIRED");
                yield null;
            }
            case REJECTED -> {
                repository.updateCandidateStatus(candidate.candidateId(), "REJECTED");
                yield null;
            }
        };

        repository.recordEvaluation(
                UUID.randomUUID(),
                candidate,
                evaluation,
                coordinateId,
                distance,
                properties,
                evaluatedAt
        );
        return true;
    }

    private UUID promote(
            CandidateEvidence candidate,
            Evaluation evaluation,
            Instant activatedAt
    ) {
        repository.retireActiveCoordinate(candidate.targetId(), activatedAt);
        UUID coordinateId = UUID.randomUUID();
        repository.createActiveCoordinate(
                coordinateId,
                candidate,
                repository.nextVersionNumber(candidate.targetId()),
                evaluation.qualityScore(),
                activatedAt
        );
        repository.updateCandidateStatus(candidate.candidateId(), "PROMOTED");
        return coordinateId;
    }

    private UUID confirm(
            CandidateEvidence candidate,
            ActiveCoordinate active,
            Evaluation evaluation
    ) {
        repository.confirmActiveCoordinate(
                active.coordinateId(),
                evaluation.qualityScore(),
                candidate.candidateSampleCount()
        );
        repository.updateCandidateStatus(candidate.candidateId(), "CONFIRMED");
        return active.coordinateId();
    }
}
