package com.address.address_system.address.importer.road;

import java.util.UUID;

public sealed interface RoadAddressImportRecord
        permits RoadAddressStagingRow, RoadAddressRejectedRow {

    UUID batchId();

    long sourceRowNumber();
}
