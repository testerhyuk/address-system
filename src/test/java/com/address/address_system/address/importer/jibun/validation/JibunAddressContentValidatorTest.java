package com.address.address_system.address.importer.jibun.validation;

import com.address.address_system.address.importer.jibun.model.JibunAddressImportRecord;
import com.address.address_system.address.importer.jibun.model.JibunAddressValidationResult;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JibunAddressContentValidatorTest {

    private final JibunAddressContentValidator validator =
            new JibunAddressContentValidator();

    @Test
    void acceptsValidJibunRowWithEmptyRiName() throws Exception {
        JibunAddressValidationResult result = validator.process(row(
                "26110110200001000000200000",
                "중앙동7가",
                "",
                "81",
                "2"
        ));

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void reportsEveryInvalidField() throws Exception {
        JibunAddressValidationResult result = validator.process(row(
                "2611",
                " ",
                "가".repeat(41),
                "10000",
                "A"
        ));

        assertThat(result.violations())
                .extracting(JibunAddressValidationResult.Violation::fieldName)
                .containsExactlyInAnyOrder(
                        "mgmt_num",
                        "b_dong_name",
                        "ri_name",
                        "jibun_main",
                        "jibun_sub"
                );
    }

    private JibunAddressImportRecord.Staging row(
            String mgmtNum,
            String bDongName,
            String riName,
            String main,
            String sub
    ) {
        return new JibunAddressImportRecord.Staging(
                UUID.fromString("00000000-0000-0000-0000-000000000802"),
                2,
                mgmtNum,
                bDongName,
                riName,
                main,
                sub
        );
    }
}
