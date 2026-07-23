package com.address.address_system.address.coordinate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.address.address_system.address.coordinate.application.DeliveryCoordinateService.Command;
import com.address.address_system.address.coordinate.application.DeliveryCoordinateService.DeliveryCoordinateException;
import com.address.address_system.address.coordinate.application.DeliveryCoordinateService.FailureCode;
import com.address.address_system.address.coordinate.config.DeliveryCoordinateProperties;
import com.address.address_system.address.coordinate.model.DeliveryCoordinateResult;
import com.address.address_system.address.coordinate.persistence.DeliveryCoordinateRepository;
import com.address.address_system.address.coordinate.persistence.DeliveryCoordinateRepository.StoredEvent;

import org.junit.jupiter.api.Test;

class DeliveryCoordinateServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final UUID EVENT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000801");
    private static final UUID TARGET_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000802");
    private static final UUID SAMPLE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000803");

    private final DeliveryCoordinateRepository repository =
            mock(DeliveryCoordinateRepository.class);
    private final DeliveryCoordinateService service = new DeliveryCoordinateService(
            repository,
            new DeliveryCoordinateProperties(
                    BigDecimal.valueOf(100),
                    Duration.ofHours(24),
                    Duration.ofMinutes(5),
                    Duration.ofDays(30),
                    Duration.ofHours(1)
            ),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void storesValidatedCoordinateAsPendingSample() {
        Command command = command(EVENT_ID, BigDecimal.valueOf(15));
        when(repository.findByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(repository.activeTargetExists(TARGET_ID)).thenReturn(true);
        when(repository.insert(
                any(), eq(EVENT_ID), eq(TARGET_ID), any(), any(), any(), any(),
                eq(NOW), eq(NOW.plus(Duration.ofDays(30))), any()
        )).thenReturn(true);

        DeliveryCoordinateResult result = service.receive(command);

        assertThat(result.processingStatus()).isEqualTo("PENDING");
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    void returnsExistingResultForIdenticalEvent() {
        Command command = command(EVENT_ID, BigDecimal.valueOf(15));
        when(repository.findByEventId(EVENT_ID)).thenReturn(
                Optional.of(new StoredEvent(
                        SAMPLE_ID,
                        EVENT_ID,
                        fingerprint(command),
                        "PENDING"
                ))
        );

        DeliveryCoordinateResult result = service.receive(command);

        assertThat(result.sampleId()).isEqualTo(SAMPLE_ID);
        assertThat(result.duplicate()).isTrue();
        verify(repository, never()).activeTargetExists(any());
    }

    @Test
    void rejectsSameEventIdWithDifferentPayload() {
        when(repository.findByEventId(EVENT_ID)).thenReturn(
                Optional.of(new StoredEvent(
                        SAMPLE_ID,
                        EVENT_ID,
                        "0".repeat(64),
                        "PENDING"
                ))
        );

        assertThatThrownBy(() -> service.receive(command(EVENT_ID, BigDecimal.valueOf(15))))
                .isInstanceOfSatisfying(DeliveryCoordinateException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(FailureCode.EVENT_ID_CONFLICT)
                );
    }

    @Test
    void rejectsInaccurateGpsBeforeDatabaseWrite() {
        assertThatThrownBy(() -> service.receive(
                command(EVENT_ID, BigDecimal.valueOf(101))
        )).isInstanceOfSatisfying(DeliveryCoordinateException.class, exception ->
                assertThat(exception.getFailureCode())
                        .isEqualTo(FailureCode.INVALID_COORDINATE_EVENT)
        );

        verify(repository, never()).insert(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void rejectsInactiveDeliveryTarget() {
        when(repository.findByEventId(EVENT_ID)).thenReturn(Optional.empty());
        when(repository.activeTargetExists(TARGET_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.receive(command(EVENT_ID, BigDecimal.valueOf(15))))
                .isInstanceOfSatisfying(DeliveryCoordinateException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(FailureCode.DELIVERY_TARGET_NOT_FOUND)
                );
    }

    private Command command(UUID eventId, BigDecimal accuracy) {
        return new Command(
                eventId,
                TARGET_ID,
                new BigDecimal("37.5665000"),
                new BigDecimal("126.9780000"),
                accuracy,
                NOW.minusSeconds(30)
        );
    }

    private String fingerprint(Command command) {
        return service.fingerprint(command);
    }
}
