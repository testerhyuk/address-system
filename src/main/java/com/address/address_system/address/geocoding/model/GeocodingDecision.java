package com.address.address_system.address.geocoding.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.address.address_system.address.geocoding.client.NominatimSearchResult;

public record GeocodingDecision(
        Status status,
        List<CandidateEvaluation> candidates
) {

    public GeocodingDecision {
        Objects.requireNonNull(status, "status must not be null");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));

        long eligibleCount = candidates.stream().filter(CandidateEvaluation::eligible).count();
        if (status == Status.MATCHED && eligibleCount != 1) {
            throw new IllegalArgumentException("MATCHED decision requires one eligible candidate");
        }
        if (status == Status.NOT_FOUND && eligibleCount != 0) {
            throw new IllegalArgumentException("NOT_FOUND decision cannot have eligible candidates");
        }
        if (status == Status.AMBIGUOUS && eligibleCount < 2) {
            throw new IllegalArgumentException("AMBIGUOUS decision requires multiple candidates");
        }
    }

    public Optional<CandidateEvaluation> matchedCandidate() {
        if (status != Status.MATCHED) {
            return Optional.empty();
        }
        return candidates.stream().filter(CandidateEvaluation::eligible).findFirst();
    }

    public enum Status {
        MATCHED,
        NOT_FOUND,
        AMBIGUOUS
    }

    public record CandidateEvaluation(
            NominatimSearchResult candidate,
            boolean eligible,
            String reasonCode
    ) {

        public CandidateEvaluation {
            Objects.requireNonNull(candidate, "candidate must not be null");
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reasonCode must not be blank");
            }
        }
    }
}
