package com.address.address_system.common.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(
        prefix = "address.api-security",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReplayProtectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReplayProtectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean claim(
            String clientId,
            UUID requestId,
            Instant requestTimestamp,
            Instant expiresAt
    ) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO address.api_request_replay (
                    client_id,
                    request_id,
                    request_timestamp,
                    expires_at
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (client_id, request_id) DO NOTHING
                """,
                clientId,
                requestId,
                Timestamp.from(requestTimestamp),
                Timestamp.from(expiresAt)
        );
        return inserted == 1;
    }

    @Scheduled(
            initialDelayString = "${address.api-security.replay-cleanup-interval:10m}",
            fixedDelayString = "${address.api-security.replay-cleanup-interval:10m}"
    )
    public void deleteExpired() {
        jdbcTemplate.update(
                "DELETE FROM address.api_request_replay WHERE expires_at < CURRENT_TIMESTAMP"
        );
    }
}
