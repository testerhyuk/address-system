package com.address.address_system.address.coordinate.persistence;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryCoordinateLookupRepository {

    private final JdbcTemplate jdbcTemplate;

    public DeliveryCoordinateLookupRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<LookupSource> findActiveTarget(UUID deliveryTargetId) {
        return jdbcTemplate.query(
                """
                SELECT target.delivery_target_id,
                       target.road_address_id,
                       coordinate.coordinate_id,
                       coordinate.version_no,
                       ST_Y(coordinate.location::geometry) AS latitude,
                       ST_X(coordinate.location::geometry) AS longitude,
                       coordinate.quality_score
                  FROM address.delivery_target target
                  JOIN address.road_address road
                    ON road.road_address_id = target.road_address_id
                   AND road.status = 'ACTIVE'
             LEFT JOIN address.delivery_coordinate_version coordinate
                    ON coordinate.delivery_target_id = target.delivery_target_id
                   AND coordinate.status = 'ACTIVE'
                   AND target.coordinate_serving_status = 'ENABLED'
                 WHERE target.delivery_target_id = ?
                   AND target.status = 'ACTIVE'
                """,
                resultSet -> resultSet.next()
                        ? Optional.of(new LookupSource(
                                resultSet.getObject("delivery_target_id", UUID.class),
                                resultSet.getObject("road_address_id", UUID.class),
                                resultSet.getObject("coordinate_id", UUID.class),
                                resultSet.getObject("version_no", Long.class),
                                resultSet.getBigDecimal("latitude"),
                                resultSet.getBigDecimal("longitude"),
                                resultSet.getBigDecimal("quality_score")
                        ))
                        : Optional.empty(),
                deliveryTargetId
        );
    }

    public record LookupSource(
            UUID deliveryTargetId,
            UUID roadAddressId,
            UUID coordinateId,
            Long versionNo,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal qualityScore
    ) {
        public boolean hasVerifiedCoordinate() {
            return coordinateId != null;
        }
    }
}
