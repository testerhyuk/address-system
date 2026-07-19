package com.address.address_system.address.importer.road.source;

import java.util.Optional;
import java.util.regex.Pattern;

public record RoadAddressManagementNumber(
        String legalAreaCode,
        String roadCode,
        String undergroundFlag,
        int buildMain,
        int buildSub
) {

    private static final Pattern MANAGEMENT_NUMBER_PATTERN = Pattern.compile("\\d{26}");

    public static Optional<RoadAddressManagementNumber> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String normalized = value.strip();
        if (!MANAGEMENT_NUMBER_PATTERN.matcher(normalized).matches()) {
            return Optional.empty();
        }

        return Optional.of(new RoadAddressManagementNumber(
                normalized.substring(0, 8),
                normalized.substring(0, 5) + normalized.substring(8, 15),
                normalized.substring(15, 16),
                Integer.parseInt(normalized.substring(16, 21)),
                Integer.parseInt(normalized.substring(21, 26))
        ));
    }
}
