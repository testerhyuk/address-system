package com.address.address_system.address.geocoding.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.address.address_system.address.geocoding.client.NominatimClient.SearchQuery;
import com.address.address_system.address.geocoding.client.NominatimSearchResult;
import com.address.address_system.address.geocoding.model.GeocodingDecision;

import org.junit.jupiter.api.Test;

class NominatimMatchEvaluatorTest {

    private final NominatimMatchEvaluator evaluator = new NominatimMatchEvaluator();
    private final SearchQuery query = new SearchQuery(
            "서울특별시",
            "중구",
            "세종대로",
            "110",
            "04524"
    );

    @Test
    void matchesOnlyOneExactHouseCandidate() {
        GeocodingDecision decision = evaluator.evaluate(query, List.of(
                candidate(1, exactAddress())
        ));

        assertThat(decision.status()).isEqualTo(GeocodingDecision.Status.MATCHED);
        assertThat(decision.matchedCandidate()).isPresent();
        assertThat(decision.candidates().get(0).reasonCode()).isEqualTo("EXACT_MATCH");
    }

    @Test
    void marksMultipleExactCandidatesAsAmbiguous() {
        GeocodingDecision decision = evaluator.evaluate(query, List.of(
                candidate(1, exactAddress()),
                candidate(2, exactAddress())
        ));

        assertThat(decision.status()).isEqualTo(GeocodingDecision.Status.AMBIGUOUS);
        assertThat(decision.candidates()).allMatch(
                GeocodingDecision.CandidateEvaluation::eligible
        );
    }

    @Test
    void rejectsCandidateWhenBuildingNumberDiffers() {
        Map<String, String> address = new java.util.HashMap<>(exactAddress());
        address.put("house_number", "111");

        GeocodingDecision decision = evaluator.evaluate(query, List.of(candidate(1, address)));

        assertThat(decision.status()).isEqualTo(GeocodingDecision.Status.NOT_FOUND);
        assertThat(decision.candidates().get(0).reasonCode())
                .isEqualTo("BUILDING_NUMBER_MISMATCH");
    }

    @Test
    void rejectsCandidateWhenPostcodeConflicts() {
        Map<String, String> address = new java.util.HashMap<>(exactAddress());
        address.put("postcode", "04520");

        GeocodingDecision decision = evaluator.evaluate(query, List.of(candidate(1, address)));

        assertThat(decision.status()).isEqualTo(GeocodingDecision.Status.NOT_FOUND);
        assertThat(decision.candidates().get(0).reasonCode())
                .isEqualTo("ZIP_CODE_MISMATCH");
    }

    @Test
    void treatsEmptySearchResultAsNotFound() {
        GeocodingDecision decision = evaluator.evaluate(query, List.of());

        assertThat(decision.status()).isEqualTo(GeocodingDecision.Status.NOT_FOUND);
        assertThat(decision.candidates()).isEmpty();
    }

    private NominatimSearchResult candidate(long placeId, Map<String, String> address) {
        return new NominatimSearchResult(
                placeId,
                "way",
                198561926L + placeId,
                "place",
                "house",
                30,
                "서울특별시청, 110, 세종대로, 중구, 서울특별시",
                new BigDecimal("37.5667893"),
                new BigDecimal("126.9784204"),
                address
        );
    }

    private Map<String, String> exactAddress() {
        return Map.of(
                "country_code", "kr",
                "city", "서울특별시",
                "borough", "중구",
                "road", "세종대로",
                "house_number", "110",
                "postcode", "04524"
        );
    }
}
