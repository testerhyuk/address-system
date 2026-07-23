package com.address.address_system.address.coordinate.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.address.address_system.address.coordinate.analysis.CoordinateAnalysisRepository.AnalysisStats;

import org.junit.jupiter.api.Test;

class CoordinateAnalysisServiceTest {

    @Test
    void runsDbscanAndCompletesAnalysisWhenEnoughSamplesExist() {
        UUID targetId = UUID.randomUUID();
        CoordinateAnalysisRepository repository = mock(CoordinateAnalysisRepository.class);
        CoordinateAnalysisProperties properties = new CoordinateAnalysisProperties(
                true, BigDecimal.valueOf(25), 5, 10,
                Duration.ofDays(30), 100, Duration.ofMinutes(10),
                3, Duration.ofMinutes(1), Duration.ofMinutes(30)
        );
        when(repository.lockActiveTarget(targetId)).thenReturn(true);
        when(repository.countPendingSamples(eq(targetId), any())).thenReturn(10);
        when(repository.readStats(any())).thenReturn(new AnalysisStats(10, 2, 1));
        CoordinateAnalysisService service = new CoordinateAnalysisService(
                repository,
                properties,
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), ZoneOffset.UTC)
        );

        assertThat(service.analyze(targetId)).isTrue();

        verify(repository).clusterSamples(any(), eq(targetId), any(), eq(properties));
        verify(repository).createCandidates(any(), eq(targetId));
        verify(repository).complete(any(), eq(new AnalysisStats(10, 2, 1)), any());
    }
}
