package com.address.address_system.address.importer.road;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class RoadAddressContentValidatorTest {

    private final RoadAddressContentValidator validator =
            new RoadAddressContentValidator(RoadAddressImportMode.DELTA);

    @Test
    void acceptsValidRoadAddress() throws Exception {
        RoadAddressStagingRow row = validRow().build();

        RoadAddressValidationResult result = validator.process(row);

        assertThat(result.row()).isSameAs(row);
        assertThat(result.isValid()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void collectsAllInvalidFormatsAndCodes() throws Exception {
        RoadAddressStagingRow row = validRow()
                .mgmtNum("123")
                .legalDongCode("26110A0100")
                .roadCode("261102")
                .undergroundFlag("9")
                .buildMain("1A")
                .buildSub("123456")
                .zipCode("ABCDE")
                .apartmentFlag("2")
                .movementReasonCode("99")
                .build();

        RoadAddressValidationResult result = validator.process(row);

        assertThat(result.isValid()).isFalse();
        assertThat(result.violations())
                .extracting(
                        RoadAddressValidationResult.Violation::fieldName,
                        RoadAddressValidationResult.Violation::reasonCode
                )
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "mgmt_num",
                                RoadAddressContentValidator.INVALID_FIELD_FORMAT
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "legal_dong_code",
                                RoadAddressContentValidator.INVALID_FIELD_FORMAT
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "road_code",
                                RoadAddressContentValidator.INVALID_FIELD_FORMAT
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "underground_flag",
                                RoadAddressContentValidator.INVALID_CODE_VALUE
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "build_main",
                                RoadAddressContentValidator.INVALID_FIELD_FORMAT
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "build_sub",
                                RoadAddressContentValidator.INVALID_FIELD_FORMAT
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "zip_code",
                                RoadAddressContentValidator.INVALID_FIELD_FORMAT
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "apartment_flag",
                                RoadAddressContentValidator.INVALID_CODE_VALUE
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "movement_reason_code",
                                RoadAddressContentValidator.INVALID_CODE_VALUE
                        )
                );
    }

    @Test
    void rejectsMissingRequiredValueOnlyOnce() throws Exception {
        RoadAddressStagingRow row = validRow().sido("   ").build();

        RoadAddressValidationResult result = validator.process(row);

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.fieldName()).isEqualTo("sido");
            assertThat(violation.reasonCode())
                    .isEqualTo(RoadAddressContentValidator.REQUIRED_FIELD_MISSING);
        });
    }

    @Test
    void rejectsImpossibleCalendarDate() throws Exception {
        RoadAddressStagingRow row = validRow().effectiveDate("20260229").build();

        RoadAddressValidationResult result = validator.process(row);

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.fieldName()).isEqualTo("effective_date");
            assertThat(violation.reasonCode())
                    .isEqualTo(RoadAddressContentValidator.INVALID_CALENDAR_DATE);
        });
    }

    @Test
    void allowsBlankOptionalNamesAndWhitespaceAroundValues() throws Exception {
        RoadAddressStagingRow row = validRow()
                .mgmtNum(" 26110101300600100000100002 ")
                .sigungu(" ")
                .bDongName(null)
                .buildNameOfficial("")
                .buildNameSigungu("  ")
                .build();

        RoadAddressValidationResult result = validator.process(row);

        assertThat(result.isValid()).isTrue();
        assertThat(result.row().mgmtNum()).startsWith(" ").endsWith(" ");
    }

    @Test
    void rejectsTextLongerThanSourceSpecification() throws Exception {
        RoadAddressStagingRow row = validRow().roadName("가".repeat(81)).build();

        RoadAddressValidationResult result = validator.process(row);

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.fieldName()).isEqualTo("road_name");
            assertThat(violation.reasonCode())
                    .isEqualTo(RoadAddressContentValidator.FIELD_LENGTH_EXCEEDED);
        });
    }

    @Test
    void fullImportAllowsBlankEffectiveDateAndMovementReason() throws Exception {
        RoadAddressContentValidator fullValidator =
                new RoadAddressContentValidator(RoadAddressImportMode.FULL);
        RoadAddressStagingRow row = validRow()
                .effectiveDate(" ")
                .movementReasonCode(null)
                .build();

        RoadAddressValidationResult result = fullValidator.process(row);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void fullImportRejectsDeltaOnlyMovementReason() throws Exception {
        RoadAddressContentValidator fullValidator =
                new RoadAddressContentValidator(RoadAddressImportMode.FULL);
        RoadAddressStagingRow row = validRow().movementReasonCode("34").build();

        RoadAddressValidationResult result = fullValidator.process(row);

        assertThat(result.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.fieldName()).isEqualTo("movement_reason_code");
            assertThat(violation.reasonCode())
                    .isEqualTo(RoadAddressContentValidator.INVALID_CODE_VALUE);
        });
    }

    private TestRowBuilder validRow() {
        return new TestRowBuilder();
    }

    private static final class TestRowBuilder {

        private UUID batchId = UUID.fromString("00000000-0000-0000-0000-000000000601");
        private long sourceRowNumber = 2;
        private String mgmtNum = "26110101300600100000100002";
        private String legalDongCode = "2611010100";
        private String sido = "부산광역시";
        private String sigungu = "중구";
        private String bDongName = "영주동";
        private String roadCode = "261102000001";
        private String roadName = "초량상로";
        private String undergroundFlag = "0";
        private String buildMain = "1";
        private String buildSub = "0";
        private String zipCode = "48910";
        private String effectiveDate = "20260718";
        private String apartmentFlag = "1";
        private String movementReasonCode = "31";
        private String buildNameOfficial = "공식건물명";
        private String buildNameSigungu = "시군구건물명";

        private TestRowBuilder mgmtNum(String value) {
            mgmtNum = value;
            return this;
        }

        private TestRowBuilder legalDongCode(String value) {
            legalDongCode = value;
            return this;
        }

        private TestRowBuilder sido(String value) {
            sido = value;
            return this;
        }

        private TestRowBuilder sigungu(String value) {
            sigungu = value;
            return this;
        }

        private TestRowBuilder bDongName(String value) {
            bDongName = value;
            return this;
        }

        private TestRowBuilder roadCode(String value) {
            roadCode = value;
            return this;
        }

        private TestRowBuilder roadName(String value) {
            roadName = value;
            return this;
        }

        private TestRowBuilder undergroundFlag(String value) {
            undergroundFlag = value;
            return this;
        }

        private TestRowBuilder buildMain(String value) {
            buildMain = value;
            return this;
        }

        private TestRowBuilder buildSub(String value) {
            buildSub = value;
            return this;
        }

        private TestRowBuilder zipCode(String value) {
            zipCode = value;
            return this;
        }

        private TestRowBuilder effectiveDate(String value) {
            effectiveDate = value;
            return this;
        }

        private TestRowBuilder apartmentFlag(String value) {
            apartmentFlag = value;
            return this;
        }

        private TestRowBuilder movementReasonCode(String value) {
            movementReasonCode = value;
            return this;
        }

        private TestRowBuilder buildNameOfficial(String value) {
            buildNameOfficial = value;
            return this;
        }

        private TestRowBuilder buildNameSigungu(String value) {
            buildNameSigungu = value;
            return this;
        }

        private RoadAddressStagingRow build() {
            return new RoadAddressStagingRow(
                    batchId,
                    sourceRowNumber,
                    mgmtNum,
                    legalDongCode,
                    sido,
                    sigungu,
                    bDongName,
                    roadCode,
                    roadName,
                    undergroundFlag,
                    buildMain,
                    buildSub,
                    zipCode,
                    effectiveDate,
                    apartmentFlag,
                    movementReasonCode,
                    buildNameOfficial,
                    buildNameSigungu
            );
        }
    }
}
