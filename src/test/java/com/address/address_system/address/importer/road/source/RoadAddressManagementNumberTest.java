package com.address.address_system.address.importer.road.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoadAddressManagementNumberTest {

    @Test
    void extractsOfficialComponentsFromTwentySixDigits() {
        RoadAddressManagementNumber result = RoadAddressManagementNumber
                .parse("26110101300600100000100002")
                .orElseThrow();

        assertThat(result.legalAreaCode()).isEqualTo("26110101");
        assertThat(result.roadCode()).isEqualTo("261103006001");
        assertThat(result.undergroundFlag()).isEqualTo("0");
        assertThat(result.buildMain()).isEqualTo(1);
        assertThat(result.buildSub()).isEqualTo(2);
    }

    @Test
    void rejectsNonNumericOrWrongLengthValue() {
        assertThat(RoadAddressManagementNumber.parse("123")).isEmpty();
        assertThat(RoadAddressManagementNumber.parse("2611010130060010000010000A")).isEmpty();
        assertThat(RoadAddressManagementNumber.parse(null)).isEmpty();
    }
}
