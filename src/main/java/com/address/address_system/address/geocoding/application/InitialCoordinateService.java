package com.address.address_system.address.geocoding.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.geocoding.client.NominatimClient;
import com.address.address_system.address.geocoding.client.NominatimClient.SearchQuery;
import com.address.address_system.address.geocoding.client.NominatimClient.Status;
import com.address.address_system.address.geocoding.client.NominatimClientException;
import com.address.address_system.address.geocoding.config.NominatimProperties;
import com.address.address_system.address.geocoding.matching.NominatimMatchEvaluator;
import com.address.address_system.address.geocoding.model.GeocodingDecision;
import com.address.address_system.address.geocoding.persistence.InitialCoordinateRepository;
import com.address.address_system.address.geocoding.persistence.InitialCoordinateRepository.AttemptFailureStatus;
import com.address.address_system.address.geocoding.persistence.InitialCoordinateRepository.InitialCoordinate;
import com.address.address_system.address.geocoding.persistence.InitialCoordinateRepository.RoadAddressSource;

import org.springframework.stereotype.Service;

@Service
public class InitialCoordinateService {

    private final InitialCoordinateRepository repository;
    private final NominatimClient nominatimClient;
    private final NominatimMatchEvaluator matchEvaluator;
    private final NominatimProperties properties;
    private final Clock clock;

    public InitialCoordinateService(
            InitialCoordinateRepository repository,
            NominatimClient nominatimClient,
            NominatimMatchEvaluator matchEvaluator,
            NominatimProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.nominatimClient = nominatimClient;
        this.matchEvaluator = matchEvaluator;
        this.properties = properties;
        this.clock = clock;
    }

    public Resolution resolve(UUID roadAddressId) {
        Objects.requireNonNull(roadAddressId, "roadAddressId must not be null");

        Optional<InitialCoordinate> existing = repository.findActiveCoordinate(roadAddressId);
        if (existing.isPresent()) {
            return available(existing.orElseThrow(), true);
        }

        RoadAddressSource source = repository.findActiveRoadAddress(roadAddressId)
                .orElseThrow(() -> new ResolutionException(
                        FailureCode.ADDRESS_NOT_FOUND,
                        "활성 도로명주소를 찾을 수 없습니다"
                ));

        Instant now = clock.instant();
        repository.findDeferredRetry(roadAddressId, now).ifPresent(retryAt -> {
            throw new ResolutionException(
                    FailureCode.RETRY_DEFERRED,
                    "Nominatim 재시도 가능 시각 전입니다. retryAt=" + retryAt
            );
        });

        Status status;
        try {
            status = nominatimClient.readStatus();
        }
        catch (NominatimClientException exception) {
            saveFailure(source, exception, null, now);
            throw requestFailed(exception);
        }

        Optional<GeocodingDecision.Status> reusable = repository.findReusableOutcome(
                roadAddressId,
                status.dataUpdated()
        );
        if (reusable.isPresent()) {
            return unresolved(reusable.orElseThrow(), true);
        }

        GeocodingDecision decision;
        try {
            SearchQuery query = toSearchQuery(source);
            decision = matchEvaluator.evaluate(query, nominatimClient.search(query));
        }
        catch (NominatimClientException exception) {
            saveFailure(source, exception, status, now);
            throw requestFailed(exception);
        }

        Optional<InitialCoordinate> coordinate = repository.saveDecision(source, status, decision);
        if (decision.status() == GeocodingDecision.Status.MATCHED) {
            return available(
                    coordinate.orElseThrow(() -> new ResolutionException(
                            FailureCode.COORDINATE_SAVE_FAILED,
                            "일치 좌표가 저장되지 않았습니다"
                    )),
                    false
            );
        }
        return unresolved(decision.status(), false);
    }

    private SearchQuery toSearchQuery(RoadAddressSource source) {
        return new SearchQuery(
                source.sido(),
                source.sigungu(),
                source.roadName(),
                source.buildingNumber(),
                source.zipCode()
        );
    }

    private void saveFailure(
            RoadAddressSource source,
            NominatimClientException exception,
            Status status,
            Instant now
    ) {
        AttemptFailureStatus failureStatus = exception.isRetryable()
                ? AttemptFailureStatus.RETRYABLE_FAILED
                : AttemptFailureStatus.FAILED;
        Instant retryAt = exception.isRetryable()
                ? now.plus(properties.retryDelay())
                : null;
        repository.saveFailure(
                source,
                failureStatus,
                exception.getReasonCode(),
                status,
                retryAt
        );
    }

    private ResolutionException requestFailed(NominatimClientException exception) {
        return new ResolutionException(
                FailureCode.NOMINATIM_REQUEST_FAILED,
                "Nominatim 요청에 실패했습니다",
                exception
        );
    }

    private Resolution available(InitialCoordinate coordinate, boolean reused) {
        return new Resolution(
                ResolutionStatus.AVAILABLE,
                coordinate.initialCoordinateId(),
                coordinate.latitude(),
                coordinate.longitude(),
                reused
        );
    }

    private Resolution unresolved(GeocodingDecision.Status status, boolean reused) {
        ResolutionStatus resolutionStatus = switch (status) {
            case NOT_FOUND -> ResolutionStatus.NOT_FOUND;
            case AMBIGUOUS -> ResolutionStatus.AMBIGUOUS;
            case MATCHED -> throw new IllegalArgumentException(
                    "MATCHED status requires a coordinate"
            );
        };
        return new Resolution(resolutionStatus, null, null, null, reused);
    }

    public enum ResolutionStatus {
        AVAILABLE,
        NOT_FOUND,
        AMBIGUOUS
    }

    public record Resolution(
            ResolutionStatus status,
            UUID initialCoordinateId,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean reused
    ) {

        public boolean available() {
            return status == ResolutionStatus.AVAILABLE;
        }
    }

    public enum FailureCode {
        ADDRESS_NOT_FOUND,
        RETRY_DEFERRED,
        NOMINATIM_REQUEST_FAILED,
        COORDINATE_SAVE_FAILED
    }

    public static class ResolutionException extends RuntimeException {

        private final FailureCode failureCode;

        public ResolutionException(FailureCode failureCode, String message) {
            super(message);
            this.failureCode = failureCode;
        }

        public ResolutionException(
                FailureCode failureCode,
                String message,
                Throwable cause
        ) {
            super(message, cause);
            this.failureCode = failureCode;
        }

        public FailureCode getFailureCode() {
            return failureCode;
        }
    }
}
