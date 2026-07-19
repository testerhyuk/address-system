package com.address.address_system.address.importer.jibun.model;

import java.util.List;
import java.util.Objects;

public record JibunAddressValidationResult(
        JibunAddressImportRecord.Staging row,
        List<Violation> violations
) {

    public JibunAddressValidationResult {
        Objects.requireNonNull(row, "row must not be null");
        violations = List.copyOf(Objects.requireNonNull(violations, "violations must not be null"));
    }

    public boolean isValid() {
        return violations.isEmpty();
    }

    public record Violation(
            String reasonCode,
            String fieldName,
            String rejectedValue,
            String reasonDetail
    ) {

        public Violation {
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            Objects.requireNonNull(fieldName, "fieldName must not be null");
            Objects.requireNonNull(reasonDetail, "reasonDetail must not be null");
        }
    }
}
