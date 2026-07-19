package com.address.address_system.address.geocoding.matching;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.address.address_system.address.geocoding.client.NominatimClient.SearchQuery;
import com.address.address_system.address.geocoding.client.NominatimSearchResult;
import com.address.address_system.address.geocoding.model.GeocodingDecision;
import com.address.address_system.address.geocoding.model.GeocodingDecision.CandidateEvaluation;

import org.springframework.stereotype.Component;

@Component
public class NominatimMatchEvaluator {

    private static final Set<String> SIDO_KEYS = Set.of(
            "state", "province", "city", "state_district"
    );
    private static final Set<String> SIGUNGU_KEYS = Set.of(
            "county", "borough", "city_district", "district", "municipality", "city"
    );

    public GeocodingDecision evaluate(
            SearchQuery query,
            List<NominatimSearchResult> candidates
    ) {
        List<CandidateEvaluation> evaluations = candidates.stream()
                .map(candidate -> evaluateCandidate(query, candidate))
                .toList();
        long eligibleCount = evaluations.stream().filter(CandidateEvaluation::eligible).count();

        GeocodingDecision.Status status = switch ((int) Math.min(eligibleCount, 2)) {
            case 0 -> GeocodingDecision.Status.NOT_FOUND;
            case 1 -> GeocodingDecision.Status.MATCHED;
            default -> GeocodingDecision.Status.AMBIGUOUS;
        };
        return new GeocodingDecision(status, evaluations);
    }

    private CandidateEvaluation evaluateCandidate(
            SearchQuery query,
            NominatimSearchResult candidate
    ) {
        Map<String, String> address = candidate.address();
        if (!"kr".equalsIgnoreCase(address.get("country_code"))) {
            return rejected(candidate, "COUNTRY_MISMATCH");
        }
        if (!isHouseLevel(candidate)) {
            return rejected(candidate, "NOT_HOUSE_LEVEL");
        }
        if (!same(query.roadName(), address.get("road"))) {
            return rejected(candidate, "ROAD_MISMATCH");
        }
        if (!sameBuildingNumber(query.buildingNumber(), address.get("house_number"))) {
            return rejected(candidate, "BUILDING_NUMBER_MISMATCH");
        }
        if (!matchesAdministrativeValue(query.sido(), address, SIDO_KEYS)) {
            return rejected(candidate, "SIDO_MISMATCH");
        }
        if (hasText(query.sigungu())
                && !matchesAdministrativeValue(query.sigungu(), address, SIGUNGU_KEYS)) {
            return rejected(candidate, "SIGUNGU_MISMATCH");
        }
        if (hasText(query.zipCode())
                && hasText(address.get("postcode"))
                && !same(query.zipCode(), address.get("postcode"))) {
            return rejected(candidate, "ZIP_CODE_MISMATCH");
        }
        return new CandidateEvaluation(candidate, true, "EXACT_MATCH");
    }

    private boolean isHouseLevel(NominatimSearchResult candidate) {
        return hasText(candidate.address().get("house_number"))
                && ("house".equalsIgnoreCase(candidate.type()) || candidate.placeRank() == 30);
    }

    private boolean matchesAdministrativeValue(
            String expected,
            Map<String, String> address,
            Set<String> keys
    ) {
        return keys.stream()
                .map(address::get)
                .filter(this::hasText)
                .anyMatch(value -> same(expected, value));
    }

    private boolean sameBuildingNumber(String expected, String actual) {
        return normalize(expected).replace('–', '-').replace('—', '-')
                .equals(normalize(actual).replace('–', '-').replace('—', '-'));
    }

    private boolean same(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private CandidateEvaluation rejected(
            NominatimSearchResult candidate,
            String reasonCode
    ) {
        return new CandidateEvaluation(candidate, false, reasonCode);
    }
}
