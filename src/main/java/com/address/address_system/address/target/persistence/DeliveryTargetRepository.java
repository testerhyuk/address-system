package com.address.address_system.address.target.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.target.model.DeliveryTargetResult;
import com.address.address_system.address.target.model.DeliveryTargetResult.TargetType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryTargetRepository {

    private static final String INSERT_TARGET = """
            INSERT INTO address.delivery_target (
                delivery_target_id,
                road_address_id,
                target_type,
                building_dong_name,
                normalized_building_dong,
                source_type
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_delivery_target_identity DO NOTHING
            RETURNING
                delivery_target_id,
                road_address_id,
                target_type,
                building_dong_name
            """;

    private static final String REACTIVATE_TARGET = """
            UPDATE address.delivery_target
            SET status = 'ACTIVE',
                version_no = CASE
                    WHEN status = 'INACTIVE' THEN version_no + 1
                    ELSE version_no
                END,
                updated_at = CURRENT_TIMESTAMP,
                inactive_at = NULL
            WHERE road_address_id = ?
              AND target_type = ?
              AND normalized_building_dong IS NOT DISTINCT FROM ?
            RETURNING
                delivery_target_id,
                road_address_id,
                target_type,
                building_dong_name
            """;

    private final JdbcTemplate jdbcTemplate;

    public DeliveryTargetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> lockActiveApartmentStatus(UUID roadAddressId) {
        List<String> statuses = jdbcTemplate.query(
                """
                SELECT apartment_status
                FROM address.road_address
                WHERE road_address_id = ?
                  AND status = 'ACTIVE'
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getString("apartment_status"),
                roadAddressId
        );
        return statuses.stream().findFirst();
    }

    public DeliveryTargetResult saveOrReactivate(
            UUID roadAddressId,
            TargetType targetType,
            String buildingDong,
            String normalizedBuildingDong,
            String sourceType
    ) {
        UUID newTargetId = UUID.randomUUID();
        List<DeliveryTargetResult> inserted = jdbcTemplate.query(
                INSERT_TARGET,
                (resultSet, rowNumber) -> map(resultSet, true),
                newTargetId,
                roadAddressId,
                targetType.name(),
                buildingDong,
                normalizedBuildingDong,
                sourceType
        );
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }

        List<DeliveryTargetResult> existing = jdbcTemplate.query(
                REACTIVATE_TARGET,
                (resultSet, rowNumber) -> map(resultSet, false),
                roadAddressId,
                targetType.name(),
                normalizedBuildingDong
        );
        if (existing.size() != 1) {
            throw new IllegalStateException("배송 대상 식별값을 저장하거나 조회할 수 없습니다");
        }
        return existing.get(0);
    }

    private DeliveryTargetResult map(java.sql.ResultSet resultSet, boolean created)
            throws java.sql.SQLException {
        return new DeliveryTargetResult(
                resultSet.getObject("delivery_target_id", UUID.class),
                resultSet.getObject("road_address_id", UUID.class),
                TargetType.valueOf(resultSet.getString("target_type")),
                resultSet.getString("building_dong_name"),
                created
        );
    }
}
