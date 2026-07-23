package com.address.address_system.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("address.api-security")
public record ApiSecurityProperties(
        boolean enabled,
        String clientId,
        String clientSecret,
        String adminClientId,
        String adminClientSecret,
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
            validateClient("clientId", clientId, clientSecret);
            validateClient("adminClientId", adminClientId, adminClientSecret);
            if (clientId.equals(adminClientId)) {
                throw new IllegalArgumentException(
                        "clientId and adminClientId must be different"
                );
            }
        }
    }

    private static void validateClient(
            String fieldName,
            String configuredClientId,
            String configuredSecret
    ) {
        if (configuredClientId == null || configuredClientId.isBlank()
                || configuredClientId.length() > 100) {
            throw new IllegalArgumentException(
                    fieldName + " must contain between 1 and 100 characters"
            );
        }
        if (configuredSecret == null
                || configuredSecret.getBytes(StandardCharsets.UTF_8).length
                < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    fieldName + " secret must contain at least 32 UTF-8 bytes"
            );
        }
    }

    byte[] clientSecretBytes(ApiClientRole role) {
        String secret = role == ApiClientRole.ADMIN
                ? adminClientSecret
                : clientSecret;
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    enum ApiClientRole {
        DELIVERY,
        ADMIN
    }
}
