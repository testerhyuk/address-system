package com.address.address_system.address.geocoding.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("address.coordinate.nominatim")
public record NominatimProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration retryDelay,
        int maxResults
) {

    public NominatimProperties {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requirePositive(retryDelay, "retryDelay");
        if (maxResults < 1 || maxResults > 40) {
            throw new IllegalArgumentException("maxResults must be between 1 and 40");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
