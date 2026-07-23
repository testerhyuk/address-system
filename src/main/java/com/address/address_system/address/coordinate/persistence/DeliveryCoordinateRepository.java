package com.address.address_system.address.coordinate.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.coordinate.config.DeliveryCoordinateProperties;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryCoordinateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final DeliveryCoordinateProperties properties;

    public DeliveryCoordinateRepository(
            JdbcTemplate jdbcTemplate,
            DeliveryCoordinateProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public boolean activeTargetExists(UUID deliveryTargetId) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM address.delivery_target target
                      JOIN address.road_address road
                        ON road.road_address_id = target.road_address_id
                       AND road.status = 'ACTIVE'
                     WHERE target.delivery_target_id = ?
                       AND target.status = 'ACTIVE'
                )
                """,
                Boolean.class,
                deliveryTargetId
        );
        return Boolean.TRUE.equals(exists);
    }

    public boolean insert(
            UUID sampleId,
            UUID eventId,
            UUID deliveryTargetId,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal gpsAccuracyMeters,
            Instant completedAt,
            Instant receivedAt,
            Instant expiresAt,
            String fingerprint
    ) {
        return jdbcTemplate.update(
                """
                INSERT INTO coordinate_raw.delivery_coordinate_sample (
                    sample_id, event_id, delivery_target_id, location,
                    gps_accuracy_meters, completed_at, received_at, expires_at,
                    request_fingerprint
                ) VALUES (
                    ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?, ?, ?, ?, ?
                )
                ON CONFLICT (event_id) DO NOTHING
                """,
                sampleId,
                eventId,
                deliveryTargetId,
                longitude,
                latitude,
                gpsAccuracyMeters,
                Timestamp.from(completedAt),
                Timestamp.from(receivedAt),
                Timestamp.from(expiresAt),
                fingerprint
        ) == 1;
    }

    public Optional<StoredEvent> findByEventId(UUID eventId) {
        return jdbcTemplate.query(
                """
                SELECT sample_id, event_id, request_fingerprint, processing_status
                  FROM coordinate_raw.delivery_coordinate_sample
                 WHERE event_id = ?
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new StoredEvent(
                                resultSet.getObject("sample_id", UUID.class),
                                resultSet.getObject("event_id", UUID.class),
                                resultSet.getString("request_fingerprint"),
                                resultSet.getString("processing_status")
                        ))
                        : Optional.empty(),
                eventId
        );
    }

    @Scheduled(
            initialDelayString = "${address.delivery-coordinate.cleanup-interval:1h}",
            fixedDelayString = "${address.delivery-coordinate.cleanup-interval:1h}"
    )
    public void deleteExpired() {
        jdbcTemplate.update(
                """
                DELETE FROM coordinate_raw.delivery_coordinate_sample
                 WHERE expires_at < CURRENT_TIMESTAMP
                """
        );
    }

    public record StoredEvent(
            UUID sampleId,
            UUID eventId,
            String fingerprint,
            String processingStatus
    ) {
    }
}
