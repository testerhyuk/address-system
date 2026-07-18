package com.address.address_system.address.importer.road;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoadAddressCsvHeaderValidatorTest {

    private static final String EXPECTED_HEADER = String.join(",", RoadAddressCsvFormat.EXPECTED_HEADER);

    private final RoadAddressCsvHeaderValidator validator = new RoadAddressCsvHeaderValidator();

    @Test
    void acceptsExpectedHeader() {
        validator.validate(EXPECTED_HEADER);
    }

    @Test
    void acceptsUtf8BomHeader() {
        validator.validate("\uFEFF" + EXPECTED_HEADER);
    }

    @Test
    void rejectsChangedColumnOrder() {
        String changedHeader = EXPECTED_HEADER.replace(
                "mgmt_num,legal_dong_code",
                "legal_dong_code,mgmt_num"
        );

        assertThatThrownBy(() -> validator.validate(changedHeader))
                .isInstanceOfSatisfying(RoadAddressImportException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(RoadAddressImportFailureCode.INVALID_HEADER)
                );
    }
}
