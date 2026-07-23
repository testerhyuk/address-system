package com.address.address_system.address.coordinate.application;

import java.util.UUID;

import com.address.address_system.address.coordinate.model.DeliveryCoordinateLookupResult;
import com.address.address_system.address.coordinate.model.DeliveryCoordinateLookupResult.CoordinateSource;
import com.address.address_system.address.coordinate.persistence.DeliveryCoordinateLookupRepository;
import com.address.address_system.address.coordinate.persistence.DeliveryCoordinateLookupRepository.LookupSource;
import com.address.address_system.address.geocoding.application.InitialCoordinateService;
import com.address.address_system.address.geocoding.application.InitialCoordinateService.Resolution;

import org.springframework.stereotype.Service;

@Service
public class DeliveryCoordinateLookupService {

    private final DeliveryCoordinateLookupRepository repository;
    private final InitialCoordinateService initialCoordinateService;

    public DeliveryCoordinateLookupService(
            DeliveryCoordinateLookupRepository repository,
            InitialCoordinateService initialCoordinateService
    ) {
        this.repository = repository;
        this.initialCoordinateService = initialCoordinateService;
    }

    public DeliveryCoordinateLookupResult find(UUID deliveryTargetId) {
        LookupSource source = repository.findActiveTarget(deliveryTargetId)
                .orElseThrow(() -> new CoordinateLookupException(
                        FailureCode.DELIVERY_TARGET_NOT_FOUND,
                        "활성 배송 대상을 찾을 수 없습니다"
                ));
        if (source.hasVerifiedCoordinate()) {
            return new DeliveryCoordinateLookupResult(
                    source.deliveryTargetId(), source.coordinateId(),
                    source.latitude(), source.longitude(), CoordinateSource.VERIFIED,
                    source.versionNo(), source.qualityScore()
            );
        }

        Resolution initial = initialCoordinateService.resolve(source.roadAddressId());
        if (!initial.available()) {
            throw new CoordinateLookupException(
                    FailureCode.COORDINATE_NOT_AVAILABLE,
                    "사용 가능한 배송 좌표가 없습니다"
            );
        }
        return new DeliveryCoordinateLookupResult(
                source.deliveryTargetId(), initial.initialCoordinateId(),
                initial.latitude(), initial.longitude(), CoordinateSource.INITIAL,
                null, null
        );
    }

    public enum FailureCode {
        DELIVERY_TARGET_NOT_FOUND,
        COORDINATE_NOT_AVAILABLE
    }

    public static class CoordinateLookupException extends RuntimeException {
        private final FailureCode failureCode;

        public CoordinateLookupException(FailureCode failureCode, String message) {
            super(message);
            this.failureCode = failureCode;
        }

        public FailureCode getFailureCode() {
            return failureCode;
        }
    }
}
