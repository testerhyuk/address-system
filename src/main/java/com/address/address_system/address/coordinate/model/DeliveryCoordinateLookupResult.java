package com.address.address_system.address.coordinate.model;

import java.math.BigDecimal;
import java.util.UUID;

public record DeliveryCoordinateLookupResult(
        UUID deliveryTargetId,
        UUID coordinateId,
        BigDecimal latitude,
        BigDecimal longitude,
        CoordinateSource source,
        Long versionNo,
        BigDecimal qualityScore
) {
    public enum CoordinateSource {
        VERIFIED,
        INITIAL
    }
}
