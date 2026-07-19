package com.address.address_system.address.search.persistence;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import com.address.address_system.address.search.model.AddressSearchResult;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AddressSearchRepository {

    private static final String SEARCH = """
            WITH matching AS (
                SELECT
                    document.road_address_id,
                    min(
                        CASE
                            WHEN document.normalized_text = ? THEN 0
                            WHEN document.normalized_text LIKE ? ESCAPE '\\' THEN 1
                            ELSE 2
                        END
                    ) AS match_level,
                    max(public.similarity(document.normalized_text, ?)) AS similarity_score
                FROM address.address_search_document document
                JOIN address.road_address active_address
                  ON active_address.road_address_id = document.road_address_id
                 AND active_address.status = 'ACTIVE'
                WHERE document.normalized_text LIKE ? ESCAPE '\\'
                GROUP BY document.road_address_id
                ORDER BY match_level, similarity_score DESC, document.road_address_id
                LIMIT ?
            )
            SELECT
                road.road_address_id,
                road_document.display_address AS road_address,
                ARRAY(
                    SELECT jibun_document.display_address
                    FROM address.address_search_document jibun_document
                    WHERE jibun_document.road_address_id = road.road_address_id
                      AND jibun_document.source_type = 'JIBUN'
                    ORDER BY jibun_document.display_address
                ) AS jibun_addresses,
                road.zip_code,
                COALESCE(road.build_name_official, road.build_name_sigungu) AS building_name,
                road.apartment_status,
                ARRAY(
                    SELECT target.building_dong_name
                    FROM address.delivery_target target
                    WHERE target.road_address_id = road.road_address_id
                      AND target.target_type = 'BUILDING_DONG'
                      AND target.status = 'ACTIVE'
                    ORDER BY target.normalized_building_dong
                ) AS known_building_dongs
            FROM matching
            JOIN address.road_address road
              ON road.road_address_id = matching.road_address_id
            JOIN address.address_search_document road_document
              ON road_document.road_address_id = road.road_address_id
             AND road_document.source_type = 'ROAD'
            ORDER BY
                matching.match_level,
                matching.similarity_score DESC,
                road_document.display_address,
                road.road_address_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public AddressSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AddressSearchResult> search(
            String normalizedQuery,
            String escapedQuery,
            int limit
    ) {
        return jdbcTemplate.query(
                SEARCH,
                (resultSet, rowNumber) -> new AddressSearchResult(
                        resultSet.getObject("road_address_id", java.util.UUID.class),
                        resultSet.getString("road_address"),
                        toStringList(resultSet.getArray("jibun_addresses")),
                        resultSet.getString("zip_code"),
                        resultSet.getString("building_name"),
                        resultSet.getString("apartment_status"),
                        toStringList(resultSet.getArray("known_building_dongs"))
                ),
                normalizedQuery,
                escapedQuery + "%",
                normalizedQuery,
                "%" + escapedQuery + "%",
                limit
        );
    }

    private List<String> toStringList(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        try {
            Object values = sqlArray.getArray();
            if (values instanceof String[] strings) {
                return List.copyOf(Arrays.asList(strings));
            }
            throw new SQLException("PostgreSQL text array was returned as an unexpected type");
        }
        finally {
            sqlArray.free();
        }
    }
}
