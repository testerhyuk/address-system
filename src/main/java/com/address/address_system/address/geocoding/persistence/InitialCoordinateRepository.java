package com.address.address_system.address.geocoding.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.geocoding.client.NominatimClient.Status;
import com.address.address_system.address.geocoding.client.NominatimSearchResult;
import com.address.address_system.address.geocoding.model.GeocodingDecision;
import com.address.address_system.address.geocoding.model.GeocodingDecision.CandidateEvaluation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InitialCoordinateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InitialCoordinateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<InitialCoordinate> findActiveCoordinate(UUID roadAddressId) {
        List<InitialCoordinate> coordinates = jdbcTemplate.query(
                """
                SELECT
                    initial_coordinate_id,
                    road_address_id,
                    version_no,
                    ST_Y(location::geometry) AS latitude,
                    ST_X(location::geometry) AS longitude,
                    source_type,
                    created_at
                FROM address.address_initial_coordinate
                WHERE road_address_id = ?
                  AND status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> new InitialCoordinate(
                        resultSet.getObject("initial_coordinate_id", UUID.class),
                        resultSet.getObject("road_address_id", UUID.class),
                        resultSet.getLong("version_no"),
                        resultSet.getBigDecimal("latitude"),
                        resultSet.getBigDecimal("longitude"),
                        resultSet.getString("source_type"),
                        resultSet.getObject("created_at", Instant.class)
                ),
                roadAddressId
        );
        return coordinates.stream().findFirst();
    }

    public Optional<RoadAddressSource> findActiveRoadAddress(UUID roadAddressId) {
        List<RoadAddressSource> addresses = jdbcTemplate.query(
                """
                SELECT
                    road_address_id,
                    sido,
                    sigungu,
                    road_name,
                    build_main,
                    build_sub,
                    zip_code
                FROM address.road_address
                WHERE road_address_id = ?
                  AND status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> new RoadAddressSource(
                        resultSet.getObject("road_address_id", UUID.class),
                        resultSet.getString("sido"),
                        resultSet.getString("sigungu"),
                        resultSet.getString("road_name"),
                        resultSet.getInt("build_main"),
                        resultSet.getInt("build_sub"),
                        resultSet.getString("zip_code")
                ),
                roadAddressId
        );
        return addresses.stream().findFirst();
    }

    public Optional<GeocodingDecision.Status> findReusableOutcome(
            UUID roadAddressId,
            Instant sourceDataUpdated
    ) {
        List<GeocodingDecision.Status> outcomes = jdbcTemplate.query(
                """
                SELECT status
                FROM address.address_geocoding_attempt
                WHERE road_address_id = ?
                  AND nominatim_data_updated = ?
                  AND status IN ('NOT_FOUND', 'AMBIGUOUS')
                ORDER BY attempted_at DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) ->
                        GeocodingDecision.Status.valueOf(resultSet.getString("status")),
                roadAddressId,
                sourceDataUpdated
        );
        return outcomes.stream().findFirst();
    }

    public Optional<Instant> findDeferredRetry(UUID roadAddressId, Instant now) {
        List<Instant> retryTimes = jdbcTemplate.query(
                """
                SELECT retry_at
                FROM address.address_geocoding_attempt
                WHERE road_address_id = ?
                  AND status = 'RETRYABLE_FAILED'
                  AND retry_at > ?
                ORDER BY attempted_at DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> resultSet.getObject("retry_at", Instant.class),
                roadAddressId,
                now
        );
        return retryTimes.stream().findFirst();
    }

    @Transactional
    public void saveFailure(
            RoadAddressSource source,
            AttemptFailureStatus failureStatus,
            String reasonCode,
            Status nominatimStatus,
            Instant retryAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO address.address_geocoding_attempt (
                    attempt_id,
                    road_address_id,
                    status,
                    query_sido,
                    query_sigungu,
                    query_road_name,
                    query_building_number,
                    query_zip_code,
                    candidate_count,
                    decision_reason_code,
                    nominatim_data_updated,
                    nominatim_software_version,
                    retry_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                source.roadAddressId(),
                failureStatus.name(),
                source.sido(),
                source.sigungu(),
                source.roadName(),
                source.buildingNumber(),
                source.zipCode(),
                reasonCode,
                nominatimStatus == null ? null : nominatimStatus.dataUpdated(),
                nominatimStatus == null ? null : nominatimStatus.softwareVersion(),
                retryAt
        );
    }

    @Transactional
    public Optional<InitialCoordinate> saveDecision(
            RoadAddressSource source,
            Status nominatimStatus,
            GeocodingDecision decision
    ) {
        UUID attemptId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO address.address_geocoding_attempt (
                    attempt_id,
                    road_address_id,
                    status,
                    query_sido,
                    query_sigungu,
                    query_road_name,
                    query_building_number,
                    query_zip_code,
                    candidate_count,
                    decision_reason_code,
                    nominatim_data_updated,
                    nominatim_software_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                attemptId,
                source.roadAddressId(),
                decision.status().name(),
                source.sido(),
                source.sigungu(),
                source.roadName(),
                source.buildingNumber(),
                source.zipCode(),
                decision.candidates().size(),
                decisionReason(decision.status()),
                nominatimStatus.dataUpdated(),
                nominatimStatus.softwareVersion()
        );

        UUID matchedCandidateId = null;
        for (int index = 0; index < decision.candidates().size(); index++) {
            CandidateEvaluation evaluation = decision.candidates().get(index);
            UUID candidateId = UUID.randomUUID();
            insertCandidate(candidateId, attemptId, index + 1, evaluation);
            if (evaluation.eligible() && matchedCandidateId == null) {
                matchedCandidateId = candidateId;
            }
        }

        if (decision.status() != GeocodingDecision.Status.MATCHED) {
            return Optional.empty();
        }
        if (matchedCandidateId == null) {
            throw new IllegalStateException("MATCHED decision has no eligible candidate");
        }

        NominatimSearchResult matched = decision.matchedCandidate()
                .orElseThrow()
                .candidate();
        jdbcTemplate.update(
                """
                INSERT INTO address.address_initial_coordinate (
                    initial_coordinate_id,
                    road_address_id,
                    version_no,
                    location,
                    source_type,
                    source_candidate_id,
                    source_data_updated,
                    source_software_version,
                    status
                ) VALUES (
                    ?,
                    ?,
                    COALESCE((
                        SELECT max(existing.version_no) + 1
                        FROM address.address_initial_coordinate existing
                        WHERE existing.road_address_id = ?
                    ), 1),
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    'OSM_NOMINATIM',
                    ?,
                    ?,
                    ?,
                    'ACTIVE'
                )
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                source.roadAddressId(),
                source.roadAddressId(),
                matched.longitude(),
                matched.latitude(),
                matchedCandidateId,
                nominatimStatus.dataUpdated(),
                nominatimStatus.softwareVersion()
        );
        return findActiveCoordinate(source.roadAddressId());
    }

    private void insertCandidate(
            UUID candidateId,
            UUID attemptId,
            int candidateOrder,
            CandidateEvaluation evaluation
    ) {
        NominatimSearchResult candidate = evaluation.candidate();
        Map<String, String> address = candidate.address();
        jdbcTemplate.update(
                """
                INSERT INTO address.address_geocoding_candidate (
                    candidate_id,
                    attempt_id,
                    candidate_order,
                    place_id,
                    osm_type,
                    osm_id,
                    category,
                    candidate_type,
                    place_rank,
                    location,
                    display_name,
                    country_code,
                    road_name,
                    house_number,
                    postcode,
                    address_details,
                    match_status,
                    match_reason_code
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?
                )
                """,
                candidateId,
                attemptId,
                candidateOrder,
                candidate.placeId(),
                candidate.osmType(),
                candidate.osmId(),
                candidate.category(),
                candidate.type(),
                candidate.placeRank(),
                candidate.longitude(),
                candidate.latitude(),
                candidate.displayName(),
                address.get("country_code"),
                address.get("road"),
                address.get("house_number"),
                address.get("postcode"),
                serializeAddress(address),
                evaluation.eligible() ? "ELIGIBLE" : "REJECTED",
                evaluation.reasonCode()
        );
    }

    private String serializeAddress(Map<String, String> address) {
        try {
            return objectMapper.writeValueAsString(address);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Nominatim 주소 상세를 직렬화할 수 없습니다", exception);
        }
    }

    private String decisionReason(GeocodingDecision.Status status) {
        return switch (status) {
            case MATCHED -> "EXACT_MATCH";
            case NOT_FOUND -> "NO_EXACT_CANDIDATE";
            case AMBIGUOUS -> "MULTIPLE_EXACT_CANDIDATES";
        };
    }

    public enum AttemptFailureStatus {
        RETRYABLE_FAILED,
        FAILED
    }

    public record RoadAddressSource(
            UUID roadAddressId,
            String sido,
            String sigungu,
            String roadName,
            int buildMain,
            int buildSub,
            String zipCode
    ) {

        public String buildingNumber() {
            return buildSub == 0 ? Integer.toString(buildMain) : buildMain + "-" + buildSub;
        }
    }

    public record InitialCoordinate(
            UUID initialCoordinateId,
            UUID roadAddressId,
            long versionNo,
            BigDecimal latitude,
            BigDecimal longitude,
            String sourceType,
            Instant createdAt
    ) {
    }
}
