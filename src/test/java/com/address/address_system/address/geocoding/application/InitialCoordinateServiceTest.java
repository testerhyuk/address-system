package com.address.address_system.address.geocoding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.geocoding.client.NominatimClient;
import com.address.address_system.address.geocoding.client.NominatimClient.Status;
import com.address.address_system.address.geocoding.client.NominatimClientException;
import com.address.address_system.address.geocoding.client.NominatimSearchResult;
import com.address.address_system.address.geocoding.config.NominatimProperties;
import com.address.address_system.address.geocoding.matching.NominatimMatchEvaluator;
import com.address.address_system.address.geocoding.model.GeocodingDecision;
import com.address.address_system.address.geocoding.persistence.InitialCoordinateRepository;
import com.address.address_system.address.geocoding.persistence.InitialCoordinateRepository.AttemptFailureStatus;
import com.address.address_system.address.geocoding.persistence.InitialCoordinateRepository.InitialCoordinate;
import com.address.address_system.address.geocoding.persistence.InitialCoordinateRepository.RoadAddressSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InitialCoordinateServiceTest {

    private static final UUID ROAD_ADDRESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000801");
    private static final UUID COORDINATE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000802");
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final Status STATUS = new Status(
            0,
            Instant.parse("2026-07-18T19:43:04Z"),
            "5.3.2"
    );

    private final InitialCoordinateRepository repository =
            mock(InitialCoordinateRepository.class);
    private final NominatimClient client = mock(NominatimClient.class);
    private final NominatimMatchEvaluator evaluator = mock(NominatimMatchEvaluator.class);
    private final NominatimProperties properties = new NominatimProperties(
            URI.create("http://localhost:8081"),
            Duration.ofSeconds(3),
            Duration.ofSeconds(10),
            Duration.ofMinutes(5),
            10
    );
    private final InitialCoordinateService service = new InitialCoordinateService(
            repository,
            client,
            evaluator,
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    private RoadAddressSource source;
    private InitialCoordinate coordinate;

    @BeforeEach
    void setUp() {
        source = new RoadAddressSource(
                ROAD_ADDRESS_ID,
                "서울특별시",
                "중구",
                "세종대로",
                110,
                0,
                "04524"
        );
        coordinate = new InitialCoordinate(
                COORDINATE_ID,
                ROAD_ADDRESS_ID,
                1,
                new BigDecimal("37.5667893"),
                new BigDecimal("126.9784204"),
                "OSM_NOMINATIM",
                NOW
        );
    }

    @Test
    void reusesActiveCoordinateWithoutCallingNominatim() {
        when(repository.findActiveCoordinate(ROAD_ADDRESS_ID))
                .thenReturn(Optional.of(coordinate));

        InitialCoordinateService.Resolution result = service.resolve(ROAD_ADDRESS_ID);

        assertThat(result.status())
                .isEqualTo(InitialCoordinateService.ResolutionStatus.AVAILABLE);
        assertThat(result.reused()).isTrue();
        verify(client, never()).readStatus();
    }

    @Test
    void reusesNotFoundOutcomeFromSameOsmVersion() {
        stubCoordinateMiss();
        when(client.readStatus()).thenReturn(STATUS);
        when(repository.findReusableOutcome(ROAD_ADDRESS_ID, STATUS.dataUpdated()))
                .thenReturn(Optional.of(GeocodingDecision.Status.NOT_FOUND));

        InitialCoordinateService.Resolution result = service.resolve(ROAD_ADDRESS_ID);

        assertThat(result.status())
                .isEqualTo(InitialCoordinateService.ResolutionStatus.NOT_FOUND);
        assertThat(result.reused()).isTrue();
        verify(client, never()).search(any());
    }

    @Test
    void searchesAndStoresOneExactCandidate() {
        stubCoordinateMiss();
        when(client.readStatus()).thenReturn(STATUS);
        when(repository.findReusableOutcome(ROAD_ADDRESS_ID, STATUS.dataUpdated()))
                .thenReturn(Optional.empty());

        NominatimSearchResult candidate = candidate();
        GeocodingDecision decision = new GeocodingDecision(
                GeocodingDecision.Status.MATCHED,
                List.of(new GeocodingDecision.CandidateEvaluation(
                        candidate,
                        true,
                        "EXACT_MATCH"
                ))
        );
        when(client.search(any())).thenReturn(List.of(candidate));
        when(evaluator.evaluate(any(), eq(List.of(candidate)))).thenReturn(decision);
        when(repository.saveDecision(source, STATUS, decision))
                .thenReturn(Optional.of(coordinate));

        InitialCoordinateService.Resolution result = service.resolve(ROAD_ADDRESS_ID);

        assertThat(result.available()).isTrue();
        assertThat(result.initialCoordinateId()).isEqualTo(COORDINATE_ID);
        assertThat(result.reused()).isFalse();
    }

    @Test
    void storesRetryableFailureAndDefersNextAttempt() {
        stubCoordinateMiss();
        NominatimClientException failure = new NominatimClientException(
                "CONNECTION_FAILED",
                true,
                "connection failed",
                null
        );
        when(client.readStatus()).thenThrow(failure);

        assertThatThrownBy(() -> service.resolve(ROAD_ADDRESS_ID))
                .isInstanceOfSatisfying(
                        InitialCoordinateService.ResolutionException.class,
                        exception -> assertThat(exception.getFailureCode()).isEqualTo(
                                InitialCoordinateService.FailureCode.NOMINATIM_REQUEST_FAILED
                        )
                );

        verify(repository).saveFailure(
                source,
                AttemptFailureStatus.RETRYABLE_FAILED,
                "CONNECTION_FAILED",
                null,
                NOW.plus(Duration.ofMinutes(5))
        );
    }

    @Test
    void doesNotCallNominatimBeforeDeferredRetryTime() {
        stubCoordinateMiss();
        when(repository.findDeferredRetry(ROAD_ADDRESS_ID, NOW))
                .thenReturn(Optional.of(NOW.plusSeconds(30)));

        assertThatThrownBy(() -> service.resolve(ROAD_ADDRESS_ID))
                .isInstanceOfSatisfying(
                        InitialCoordinateService.ResolutionException.class,
                        exception -> assertThat(exception.getFailureCode())
                                .isEqualTo(InitialCoordinateService.FailureCode.RETRY_DEFERRED)
                );

        verify(client, never()).readStatus();
    }

    private void stubCoordinateMiss() {
        when(repository.findActiveCoordinate(ROAD_ADDRESS_ID)).thenReturn(Optional.empty());
        when(repository.findActiveRoadAddress(ROAD_ADDRESS_ID)).thenReturn(Optional.of(source));
        when(repository.findDeferredRetry(ROAD_ADDRESS_ID, NOW)).thenReturn(Optional.empty());
    }

    private NominatimSearchResult candidate() {
        return new NominatimSearchResult(
                1,
                "way",
                198561926L,
                "place",
                "house",
                30,
                "서울특별시청, 110, 세종대로, 중구, 서울특별시",
                new BigDecimal("37.5667893"),
                new BigDecimal("126.9784204"),
                Map.of(
                        "country_code", "kr",
                        "city", "서울특별시",
                        "borough", "중구",
                        "road", "세종대로",
                        "house_number", "110",
                        "postcode", "04524"
                )
        );
    }
}
