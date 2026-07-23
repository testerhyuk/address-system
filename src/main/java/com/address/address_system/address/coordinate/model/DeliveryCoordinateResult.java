package com.address.address_system.address.coordinate.model;

import java.util.UUID;

public record DeliveryCoordinateResult(
        UUID sampleId,
        UUID eventId,
        String processingStatus,
        boolean duplicate
) {
}
