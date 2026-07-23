package com.address.address_system.common.security;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("address.api-security.rate-limit")
public record ApiRateLimitProperties(
        boolean enabled,
        Policy addressSearch,
        Policy deliveryTarget,
        Policy deliveryCoordinate,
        Policy defaultPolicy,
        Duration staleBucketRetention,
        Duration cleanupInterval
) {

    public ApiRateLimitProperties {
        if (staleBucketRetention == null || staleBucketRetention.isNegative()
                || staleBucketRetention.isZero()) {
            throw new IllegalArgumentException("staleBucketRetention must be positive");
        }
        if (cleanupInterval == null || cleanupInterval.isNegative()
                || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("cleanupInterval must be positive");
        }
        if (enabled && (addressSearch == null || deliveryTarget == null
                || deliveryCoordinate == null
                || defaultPolicy == null)) {
            throw new IllegalArgumentException(
                    "all API rate limit policies must be configured when enabled"
            );
        }
    }

    public record Policy(int capacity, BigDecimal refillTokensPerSecond) {

        public Policy {
            if (capacity <= 0) {
                throw new IllegalArgumentException("rate limit capacity must be positive");
            }
            if (refillTokensPerSecond == null
                    || refillTokensPerSecond.signum() <= 0) {
                throw new IllegalArgumentException(
                        "refillTokensPerSecond must be positive"
                );
            }
        }
    }
}
