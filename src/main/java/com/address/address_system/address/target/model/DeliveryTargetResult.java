package com.address.address_system.address.target.model;

import java.util.Objects;
import java.util.UUID;

public record DeliveryTargetResult(
        UUID deliveryTargetId,
        UUID roadAddressId,
        TargetType targetType,
        String buildingDong,
        boolean created
) {

    public DeliveryTargetResult {
        Objects.requireNonNull(deliveryTargetId, "deliveryTargetId must not be null");
        Objects.requireNonNull(roadAddressId, "roadAddressId must not be null");
        Objects.requireNonNull(targetType, "targetType must not be null");
    }

    public enum TargetType {
        BUILDING,
        BUILDING_DONG
    }
}
