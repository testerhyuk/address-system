package com.address.address_system.address.importer.road;

import java.util.UUID;

public record RoadAddressRejectedRow(
        UUID batchId,
        long sourceRowNumber,
        String reasonCode,
        String fieldName,
        String rejectedValue,
        String reasonDetail
) implements RoadAddressImportRecord {
}
