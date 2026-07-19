package com.address.address_system.address.importer.road.source;

import java.nio.file.Path;

public record RoadAddressImportFile(
        Path path,
        String fileName,
        long fileSizeBytes,
        String sha256,
        RoadAddressCsvFormat.Schema schema
) {
}
