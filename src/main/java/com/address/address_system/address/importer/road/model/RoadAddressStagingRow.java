package com.address.address_system.address.importer.road.model;

import java.util.UUID;

public record RoadAddressStagingRow(
        UUID batchId,
        long sourceRowNumber,
        String mgmtNum,
        String legalAreaCode,
        String legalDongCode,
        String sido,
        String sigungu,
        String bDongName,
        String roadCode,
        String roadName,
        String undergroundFlag,
        String buildMain,
        String buildSub,
        String zipCode,
        String effectiveDate,
        String apartmentFlag,
        String movementReasonCode,
        String buildNameOfficial,
        String buildNameSigungu
) implements RoadAddressImportRecord {
}
