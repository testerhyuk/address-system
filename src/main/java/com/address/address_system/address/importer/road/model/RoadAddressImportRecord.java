package com.address.address_system.address.importer.road.model;

import java.util.UUID;

public sealed interface RoadAddressImportRecord
        permits RoadAddressStagingRow, RoadAddressRejectedRow {

    UUID batchId();

    long sourceRowNumber();
}
