package com.address.address_system.address.search.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AddressSearchResult(
        UUID roadAddressId,
        String roadAddress,
        List<String> jibunAddresses,
        String zipCode,
        String buildingName,
        String apartmentStatus,
        List<String> knownBuildingDongs
) {

    public AddressSearchResult {
        Objects.requireNonNull(roadAddressId, "roadAddressId must not be null");
        Objects.requireNonNull(roadAddress, "roadAddress must not be null");
        Objects.requireNonNull(zipCode, "zipCode must not be null");
        Objects.requireNonNull(apartmentStatus, "apartmentStatus must not be null");
        jibunAddresses = List.copyOf(Objects.requireNonNull(
                jibunAddresses,
                "jibunAddresses must not be null"
        ));
        knownBuildingDongs = List.copyOf(Objects.requireNonNull(
                knownBuildingDongs,
                "knownBuildingDongs must not be null"
        ));
    }
}
