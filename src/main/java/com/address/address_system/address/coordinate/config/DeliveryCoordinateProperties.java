package com.address.address_system.address.coordinate.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("address.delivery-coordinate")
public record DeliveryCoordinateProperties(
        BigDecimal maxGpsAccuracyMeters,
        Duration maxEventAge,
        Duration allowedFutureSkew,
        Duration rawRetention,
        Duration cleanupInterval
) {
    private static final Duration MAXIMUM_RAW_RETENTION = Duration.ofDays(30);

    public DeliveryCoordinateProperties {
        requirePositive(maxGpsAccuracyMeters, "maxGpsAccuracyMeters");
        requirePositive(maxEventAge, "maxEventAge");
        requirePositive(allowedFutureSkew, "allowedFutureSkew");
        requirePositive(rawRetention, "rawRetention");
        requirePositive(cleanupInterval, "cleanupInterval");
        if (rawRetention.compareTo(MAXIMUM_RAW_RETENTION) > 0) {
            throw new IllegalArgumentException(
                    "rawRetention must not exceed 30 days"
            );
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
