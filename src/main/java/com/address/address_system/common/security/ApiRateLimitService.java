package com.address.address_system.common.security;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.address.address_system.common.security.ApiRateLimitProperties.Policy;
import com.address.address_system.common.security.ApiRateLimitRepository.Consumption;

import org.springframework.stereotype.Service;

@Service
public class ApiRateLimitService {

    private static final String ADDRESS_SEARCH_KEY = "ADDRESS_SEARCH";
    private static final String DELIVERY_TARGET_KEY = "DELIVERY_TARGET";
    private static final String DEFAULT_KEY = "DEFAULT_API";

    private final ApiRateLimitProperties properties;
    private final ApiRateLimitRepository repository;

    public ApiRateLimitService(
            ApiRateLimitProperties properties,
            ApiRateLimitRepository repository
    ) {
        this.properties = properties;
        this.repository = repository;
    }

    public Decision consume(String clientId, String method, String requestUri) {
        if (!properties.enabled()) {
            return Decision.disabled();
        }

        PolicySelection selection = selectPolicy(method, requestUri);
        Consumption consumption = repository.consume(
                clientId,
                selection.serviceKey(),
                selection.policy()
        );
        int remaining = consumption.remainingTokens()
                .setScale(0, RoundingMode.FLOOR)
                .max(BigDecimal.ZERO)
                .intValue();
        if (consumption.allowed()) {
            return Decision.allowed(selection.policy().capacity(), remaining);
        }

        BigDecimal tokensNeeded = BigDecimal.ONE.subtract(
                consumption.remainingTokens().max(BigDecimal.ZERO)
        );
        long retryAfterSeconds = tokensNeeded.divide(
                selection.policy().refillTokensPerSecond(),
                0,
                RoundingMode.CEILING
        ).max(BigDecimal.ONE).longValue();
        return Decision.rejected(
                selection.policy().capacity(),
                remaining,
                retryAfterSeconds
        );
    }

    private PolicySelection selectPolicy(String method, String requestUri) {
        if ("GET".equalsIgnoreCase(method)
                && "/api/v1/addresses".equals(requestUri)) {
            return new PolicySelection(ADDRESS_SEARCH_KEY, properties.addressSearch());
        }
        if ("POST".equalsIgnoreCase(method)
                && "/api/v1/delivery-targets".equals(requestUri)) {
            return new PolicySelection(
                    DELIVERY_TARGET_KEY,
                    properties.deliveryTarget()
            );
        }
        return new PolicySelection(DEFAULT_KEY, properties.defaultPolicy());
    }

    private record PolicySelection(String serviceKey, Policy policy) {
    }

    public record Decision(
            boolean enabled,
            boolean allowed,
            int limit,
            int remaining,
            long retryAfterSeconds
    ) {

        static Decision disabled() {
            return new Decision(false, true, 0, 0, 0);
        }

        static Decision allowed(int limit, int remaining) {
            return new Decision(true, true, limit, remaining, 0);
        }

        static Decision rejected(int limit, int remaining, long retryAfterSeconds) {
            return new Decision(true, false, limit, remaining, retryAfterSeconds);
        }
    }
}
