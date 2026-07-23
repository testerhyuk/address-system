package com.address.address_system.address.coordinate.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.coordinate.quality.CoordinateQualityRepository.ActiveCoordinate;
import com.address.address_system.address.coordinate.quality.CoordinateQualityRepository.CandidateEvidence;
import com.address.address_system.address.coordinate.quality.CoordinateQualityRepository.TargetState;

import org.junit.jupiter.api.Test;

class CoordinateQualityServiceTest {

    @Test
    void createsANewActiveVersionWhenTheCandidatePassesThePolicy() {
        UUID candidateId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        CandidateEvidence evidence = evidence(candidateId, targetId);
        CoordinateQualityRepository repository = mock(CoordinateQualityRepository.class);
        CoordinateQualityProperties properties = properties();
        when(repository.lockGeneratedCandidate(candidateId))
                .thenReturn(Optional.of(evidence));
        when(repository.lockActiveTarget(targetId))
                .thenReturn(Optional.of(new TargetState(true)));
        when(repository.findActiveCoordinate(targetId, candidateId))
                .thenReturn(Optional.empty());
        when(repository.nextVersionNumber(targetId)).thenReturn(1L);
        CoordinateQualityService service = service(repository, properties);

        assertThat(service.evaluate(candidateId)).isTrue();

        verify(repository).retireActiveCoordinate(eq(targetId), any());
        verify(repository).createActiveCoordinate(
                any(), eq(evidence), eq(1L), eq(new BigDecimal("0.8625")), any()
        );
        verify(repository).updateCandidateStatus(candidateId, "PROMOTED");
        verify(repository).recordEvaluation(
                any(), eq(evidence), any(), any(), eq(null), eq(properties), any()
        );
    }

    @Test
    void replacesEvidenceWithTheLatestFullAnalysisForANearbyCandidate() {
        UUID candidateId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        CandidateEvidence evidence = evidence(candidateId, targetId);
        CoordinateQualityRepository repository = mock(CoordinateQualityRepository.class);
        CoordinateQualityProperties properties = properties();
        when(repository.lockGeneratedCandidate(candidateId))
                .thenReturn(Optional.of(evidence));
        when(repository.lockActiveTarget(targetId))
                .thenReturn(Optional.of(new TargetState(true)));
        when(repository.findActiveCoordinate(targetId, candidateId))
                .thenReturn(Optional.of(new ActiveCoordinate(
                        activeId, 1, new BigDecimal("0.8000"), 20,
                        new BigDecimal("6")
                )));
        CoordinateQualityService service = service(repository, properties);

        assertThat(service.evaluate(candidateId)).isTrue();

        verify(repository).confirmActiveCoordinate(
                activeId, new BigDecimal("0.8625"), 9
        );
        verify(repository).updateCandidateStatus(candidateId, "CONFIRMED");
        verify(repository, never()).createActiveCoordinate(
                any(), any(), eq(1L), any(), any()
        );
    }

    @Test
    void holdsAQualifiedCandidateForReviewWhileServingIsSuspended() {
        UUID candidateId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        CandidateEvidence evidence = evidence(candidateId, targetId);
        CoordinateQualityRepository repository = mock(CoordinateQualityRepository.class);
        CoordinateQualityProperties properties = properties();
        when(repository.lockGeneratedCandidate(candidateId))
                .thenReturn(Optional.of(evidence));
        when(repository.lockActiveTarget(targetId))
                .thenReturn(Optional.of(new TargetState(false)));
        when(repository.findActiveCoordinate(targetId, candidateId))
                .thenReturn(Optional.empty());
        CoordinateQualityService service = service(repository, properties);

        assertThat(service.evaluate(candidateId)).isTrue();

        verify(repository).updateCandidateStatus(candidateId, "REVIEW_REQUIRED");
        verify(repository, never()).createActiveCoordinate(
                any(), any(), anyLong(), any(), any()
        );
    }

    private CoordinateQualityService service(
            CoordinateQualityRepository repository,
            CoordinateQualityProperties properties
    ) {
        return new CoordinateQualityService(
                repository,
                new CoordinateQualityPolicy(properties),
                properties,
                Clock.fixed(Instant.parse("2026-07-22T04:00:00Z"), ZoneOffset.UTC)
        );
    }

    private CandidateEvidence evidence(UUID candidateId, UUID targetId) {
        return new CandidateEvidence(
                candidateId,
                UUID.randomUUID(),
                targetId,
                9,
                10,
                1,
                new BigDecimal("5"),
                true,
                0
        );
    }

    private CoordinateQualityProperties properties() {
        return new CoordinateQualityProperties(
                true,
                5,
                10,
                new BigDecimal("20"),
                new BigDecimal("0.30"),
                new BigDecimal("0.60"),
                new BigDecimal("0.15"),
                new BigDecimal("0.75"),
                new BigDecimal("0.55"),
                new BigDecimal("10"),
                new BigDecimal("100"),
                100,
                Duration.ofMinutes(10),
                "v1",
                3,
                Duration.ofMinutes(1),
                Duration.ofMinutes(30)
        );
    }
}
