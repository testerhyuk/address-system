package com.address.address_system.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("address.api-security")
public record ApiSecurityProperties(
        boolean enabled,
        String clientId,
        String clientSecret,
        Duration allowedClockSkew,
        Duration replayRetention,
        Duration replayCleanupInterval,
        int maxRequestBodyBytes
) {

    private static final int MINIMUM_SECRET_BYTES = 32;

    public ApiSecurityProperties {
        if (allowedClockSkew == null || allowedClockSkew.isNegative()
                || allowedClockSkew.isZero()) {
            throw new IllegalArgumentException("allowedClockSkew must be positive");
        }
        if (replayRetention == null
                || replayRetention.compareTo(allowedClockSkew.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "replayRetention must be at least twice allowedClockSkew"
            );
        }
        if (replayCleanupInterval == null || replayCleanupInterval.isNegative()
                || replayCleanupInterval.isZero()) {
            throw new IllegalArgumentException("replayCleanupInterval must be positive");
        }
        if (maxRequestBodyBytes < 1) {
            throw new IllegalArgumentException("maxRequestBodyBytes must be positive");
        }
        if (enabled) {
            if (clientId == null || clientId.isBlank() || clientId.length() > 100) {
                throw new IllegalArgumentException(
                        "clientId must contain between 1 and 100 characters"
                );
            }
            if (clientSecret == null
                    || clientSecret.getBytes(StandardCharsets.UTF_8).length
                    < MINIMUM_SECRET_BYTES) {
                throw new IllegalArgumentException(
                        "clientSecret must contain at least 32 UTF-8 bytes"
                );
            }
        }
    }

    byte[] clientSecretBytes() {
        return clientSecret.getBytes(StandardCharsets.UTF_8);
    }
}
