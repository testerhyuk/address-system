package com.address.address_system.address.coordinate.failure;

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
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.coordinate.analysis.CoordinateAnalysisProperties;
import com.address.address_system.address.coordinate.failure.CoordinateProcessingFailureRepository.FailureState;
import com.address.address_system.address.coordinate.quality.CoordinateQualityProperties;

import org.junit.jupiter.api.Test;

class CoordinateProcessingFailureServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");

    @Test
    void schedulesTheFirstFailureWithoutStoringTheExceptionMessage() {
        UUID targetId = UUID.randomUUID();
        CoordinateProcessingFailureRepository repository =
                mock(CoordinateProcessingFailureRepository.class);
        when(repository.lock("ANALYSIS", targetId, null))
                .thenReturn(Optional.empty());
        CoordinateProcessingFailureService service = service(repository);

        service.recordAnalysisFailure(
                targetId,
                new IllegalStateException("must not be stored")
        );

        verify(repository).insert(
                any(),
                eq("ANALYSIS"),
                eq(targetId),
                eq(null),
                eq("RETRY_SCHEDULED"),
                eq(1),
                eq("IllegalStateException"),
                eq(NOW),
                eq(NOW.plus(Duration.ofMinutes(1)))
        );
    }

    @Test
    void exhaustsTheFailureAfterTheConfiguredMaximumAttempts() {
        UUID targetId = UUID.randomUUID();
        UUID failureId = UUID.randomUUID();
        CoordinateProcessingFailureRepository repository =
                mock(CoordinateProcessingFailureRepository.class);
        when(repository.lock("ANALYSIS", targetId, null)).thenReturn(Optional.of(
                new FailureState(failureId, "RETRY_SCHEDULED", 2)
        ));
        CoordinateProcessingFailureService service = service(repository);

        service.recordAnalysisFailure(targetId, new RuntimeException("ignored"));

        verify(repository).updateFailure(
                failureId,
                "EXHAUSTED",
                3,
                "RuntimeException",
                NOW,
                null
        );
    }

    private CoordinateProcessingFailureService service(
            CoordinateProcessingFailureRepository repository
    ) {
        return new CoordinateProcessingFailureService(
                repository,
                new CoordinateAnalysisProperties(
                        true, new BigDecimal("25"), 5, 10,
                        Duration.ofDays(30), 100, Duration.ofMinutes(10),
                        3, Duration.ofMinutes(1), Duration.ofMinutes(30)
                ),
                new CoordinateQualityProperties(
                        true, 5, 10, new BigDecimal("20"),
                        new BigDecimal("0.30"), new BigDecimal("0.60"),
                        new BigDecimal("0.15"), new BigDecimal("0.75"),
                        new BigDecimal("0.55"), new BigDecimal("10"),
                        new BigDecimal("100"), 100, Duration.ofMinutes(10),
                        "v1", 3, Duration.ofMinutes(1), Duration.ofMinutes(30)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
