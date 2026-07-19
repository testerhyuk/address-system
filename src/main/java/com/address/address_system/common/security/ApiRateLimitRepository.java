package com.address.address_system.common.security;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

@Repository
public class ApiRateLimitRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ApiRateLimitProperties properties;

    public ApiRateLimitRepository(
            JdbcTemplate jdbcTemplate,
            ApiRateLimitProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public Consumption consume(
            String clientId,
            String serviceKey,
            ApiRateLimitProperties.Policy policy
    ) {
        return jdbcTemplate.query(
                """
                SELECT allowed, remaining_tokens
                  FROM address.consume_api_rate_limit_token(?, ?, ?, ?)
                """,
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException(
                                "rate limit function returned no result"
                        );
                    }
                    return new Consumption(
                            resultSet.getBoolean("allowed"),
                            resultSet.getBigDecimal("remaining_tokens")
                    );
                },
                clientId,
                serviceKey,
                policy.capacity(),
                policy.refillTokensPerSecond()
        );
    }

    @Scheduled(
            initialDelayString = "${address.api-security.rate-limit.cleanup-interval:1h}",
            fixedDelayString = "${address.api-security.rate-limit.cleanup-interval:1h}"
    )
    public void deleteStaleBuckets() {
        jdbcTemplate.update(
                """
                DELETE FROM address.api_rate_limit_bucket
                 WHERE updated_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
                """,
                properties.staleBucketRetention().toSeconds()
        );
    }

    public record Consumption(boolean allowed, BigDecimal remainingTokens) {
    }
}
