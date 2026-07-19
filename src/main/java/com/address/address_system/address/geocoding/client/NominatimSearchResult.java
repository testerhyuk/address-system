package com.address.address_system.address.geocoding.client;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NominatimSearchResult(
        @JsonProperty("place_id") long placeId,
        @JsonProperty("osm_type") String osmType,
        @JsonProperty("osm_id") Long osmId,
        String category,
        String type,
        @JsonProperty("place_rank") int placeRank,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("lat") BigDecimal latitude,
        @JsonProperty("lon") BigDecimal longitude,
        Map<String, String> address
) {

    public NominatimSearchResult {
        if (placeId <= 0) {
            throw new IllegalArgumentException("placeId must be positive");
        }
        if (placeRank < 0 || placeRank > 30) {
            throw new IllegalArgumentException("placeRank must be between 0 and 30");
        }
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(latitude, "latitude must not be null");
        Objects.requireNonNull(longitude, "longitude must not be null");
        if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new IllegalArgumentException("latitude is outside the valid range");
        }
        if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("longitude is outside the valid range");
        }
        address = address == null ? Map.of() : Map.copyOf(address);
    }
}
