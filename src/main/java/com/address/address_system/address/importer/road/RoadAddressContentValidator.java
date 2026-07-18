package com.address.address_system.address.importer.road;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.batch.infrastructure.item.ItemProcessor;

public class RoadAddressContentValidator
        implements ItemProcessor<RoadAddressStagingRow, RoadAddressValidationResult> {

    static final String REQUIRED_FIELD_MISSING = "REQUIRED_FIELD_MISSING";
    static final String INVALID_FIELD_FORMAT = "INVALID_FIELD_FORMAT";
    static final String FIELD_LENGTH_EXCEEDED = "FIELD_LENGTH_EXCEEDED";
    static final String INVALID_CODE_VALUE = "INVALID_CODE_VALUE";
    static final String INVALID_CALENDAR_DATE = "INVALID_CALENDAR_DATE";
    static final String MANAGEMENT_NUMBER_COMPONENT_MISMATCH =
            "MANAGEMENT_NUMBER_COMPONENT_MISMATCH";

    private static final Pattern MGMT_NUMBER_PATTERN = Pattern.compile("\\d{26}");
    private static final Pattern LEGAL_AREA_CODE_PATTERN = Pattern.compile("\\d{8}");
    private static final Pattern LEGAL_DONG_CODE_PATTERN = Pattern.compile("\\d{10}");
    private static final Pattern ROAD_CODE_PATTERN = Pattern.compile("\\d{12}");
    private static final Pattern BUILDING_NUMBER_PATTERN = Pattern.compile("\\d{1,5}");
    private static final Pattern ZIP_CODE_PATTERN = Pattern.compile("\\d{5}");
    private static final Pattern EFFECTIVE_DATE_PATTERN = Pattern.compile("\\d{8}");

    private static final Set<String> UNDERGROUND_FLAGS = Set.of("0", "1", "2", "3");
    private static final Set<String> APARTMENT_FLAGS = Set.of("0", "1");
    private static final Set<String> MOVEMENT_REASON_CODES = Set.of("31", "34", "63");

    private static final DateTimeFormatter BASIC_DATE_FORMATTER =
            DateTimeFormatter.BASIC_ISO_DATE.withResolverStyle(ResolverStyle.STRICT);

    private final RoadAddressImportMode importMode;

    public RoadAddressContentValidator(RoadAddressImportMode importMode) {
        this.importMode = Objects.requireNonNull(importMode, "importMode");
    }

    @Override
    public RoadAddressValidationResult process(RoadAddressStagingRow row) {
        List<RoadAddressValidationResult.Violation> violations = new ArrayList<>();

        validateRequiredPattern(
                violations,
                "mgmt_num",
                row.mgmtNum(),
                MGMT_NUMBER_PATTERN,
                "도로명주소관리번호는 26자리 숫자여야 합니다"
        );
        validateRequiredPattern(
                violations,
                "legal_area_code",
                row.legalAreaCode(),
                LEGAL_AREA_CODE_PATTERN,
                "법정지역코드는 8자리 숫자여야 합니다"
        );
        validateOptionalPattern(
                violations,
                "legal_dong_code",
                row.legalDongCode(),
                LEGAL_DONG_CODE_PATTERN,
                "법정동코드는 값이 있는 경우 10자리 숫자여야 합니다"
        );
        validateRequiredText(violations, "sido", row.sido(), 40, "시도명");
        validateOptionalText(violations, "sigungu", row.sigungu(), 40, "시군구명");
        validateOptionalText(violations, "b_dong_name", row.bDongName(), 40, "법정읍면동명");
        validateRequiredPattern(
                violations,
                "road_code",
                row.roadCode(),
                ROAD_CODE_PATTERN,
                "도로명코드는 12자리 숫자여야 합니다"
        );
        validateRequiredText(violations, "road_name", row.roadName(), 80, "도로명");
        validateRequiredCode(
                violations,
                "underground_flag",
                row.undergroundFlag(),
                UNDERGROUND_FLAGS,
                "지하여부는 0, 1, 2, 3 중 하나여야 합니다"
        );
        validateRequiredPattern(
                violations,
                "build_main",
                row.buildMain(),
                BUILDING_NUMBER_PATTERN,
                "건물본번은 1~5자리 숫자여야 합니다"
        );
        validateRequiredPattern(
                violations,
                "build_sub",
                row.buildSub(),
                BUILDING_NUMBER_PATTERN,
                "건물부번은 1~5자리 숫자여야 합니다"
        );
        validateRequiredPattern(
                violations,
                "zip_code",
                row.zipCode(),
                ZIP_CODE_PATTERN,
                "우편번호는 5자리 숫자여야 합니다"
        );
        validateEffectiveDate(violations, row.effectiveDate());
        validateApartmentFlag(violations, row.apartmentFlag());
        validateMovementReasonCode(violations, row.movementReasonCode());
        validateOptionalText(
                violations,
                "build_nm_official",
                row.buildNameOfficial(),
                400,
                "건축물대장건물명"
        );
        validateOptionalText(
                violations,
                "build_nm_sgg",
                row.buildNameSigungu(),
                400,
                "시군구용건물명"
        );
        validateManagementNumberComponents(violations, row);

        return new RoadAddressValidationResult(row, violations);
    }

    private void validateManagementNumberComponents(
            List<RoadAddressValidationResult.Violation> violations,
            RoadAddressStagingRow row
    ) {
        Optional<RoadAddressManagementNumber> parsed =
                RoadAddressManagementNumber.parse(row.mgmtNum());
        if (parsed.isEmpty()) {
            return;
        }

        RoadAddressManagementNumber managementNumber = parsed.get();
        validateComponent(
                violations,
                "legal_area_code",
                row.legalAreaCode(),
                managementNumber.legalAreaCode()
        );
        validateComponent(
                violations,
                "road_code",
                row.roadCode(),
                managementNumber.roadCode()
        );
        validateComponent(
                violations,
                "underground_flag",
                row.undergroundFlag(),
                managementNumber.undergroundFlag()
        );
        validateNumericComponent(
                violations,
                "build_main",
                row.buildMain(),
                managementNumber.buildMain()
        );
        validateNumericComponent(
                violations,
                "build_sub",
                row.buildSub(),
                managementNumber.buildSub()
        );

        String legalDongCode = normalize(row.legalDongCode());
        if (legalDongCode != null
                && LEGAL_DONG_CODE_PATTERN.matcher(legalDongCode).matches()
                && !legalDongCode.startsWith(managementNumber.legalAreaCode())) {
            addComponentMismatch(
                    violations,
                    "legal_dong_code",
                    row.legalDongCode(),
                    "법정동코드 앞 8자리가 관리번호의 법정지역코드와 일치하지 않습니다"
            );
        }
    }

    private void validateComponent(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String actualValue,
            String expectedValue
    ) {
        String normalized = normalize(actualValue);
        if (normalized != null && !expectedValue.equals(normalized)) {
            addComponentMismatch(
                    violations,
                    fieldName,
                    actualValue,
                    "관리번호에서 추출한 값 " + expectedValue + "와 일치하지 않습니다"
            );
        }
    }

    private void validateNumericComponent(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String actualValue,
            int expectedValue
    ) {
        String normalized = normalize(actualValue);
        if (normalized == null || !BUILDING_NUMBER_PATTERN.matcher(normalized).matches()) {
            return;
        }
        if (Integer.parseInt(normalized) != expectedValue) {
            addComponentMismatch(
                    violations,
                    fieldName,
                    actualValue,
                    "관리번호에서 추출한 건물번호 " + expectedValue + "와 일치하지 않습니다"
            );
        }
    }

    private void addComponentMismatch(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String value,
            String detail
    ) {
        violations.add(new RoadAddressValidationResult.Violation(
                MANAGEMENT_NUMBER_COMPONENT_MISMATCH,
                fieldName,
                value,
                detail
        ));
    }

    private void validateRequiredPattern(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String value,
            Pattern pattern,
            String formatMessage
    ) {
        String normalized = normalize(value);
        if (normalized == null) {
            addRequiredViolation(violations, fieldName, value);
            return;
        }
        if (!pattern.matcher(normalized).matches()) {
            violations.add(new RoadAddressValidationResult.Violation(
                    INVALID_FIELD_FORMAT,
                    fieldName,
                    value,
                    formatMessage
            ));
        }
    }

    private void validateOptionalPattern(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String value,
            Pattern pattern,
            String formatMessage
    ) {
        String normalized = normalize(value);
        if (normalized != null && !pattern.matcher(normalized).matches()) {
            violations.add(new RoadAddressValidationResult.Violation(
                    INVALID_FIELD_FORMAT,
                    fieldName,
                    value,
                    formatMessage
            ));
        }
    }

    private void validateRequiredText(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String value,
            int maxLength,
            String displayName
    ) {
        String normalized = normalize(value);
        if (normalized == null) {
            addRequiredViolation(violations, fieldName, value);
            return;
        }
        validateLength(violations, fieldName, value, normalized, maxLength, displayName);
    }

    private void validateOptionalText(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String value,
            int maxLength,
            String displayName
    ) {
        String normalized = normalize(value);
        if (normalized != null) {
            validateLength(violations, fieldName, value, normalized, maxLength, displayName);
        }
    }

    private void validateRequiredCode(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String value,
            Set<String> allowedValues,
            String codeMessage
    ) {
        String normalized = normalize(value);
        if (normalized == null) {
            addRequiredViolation(violations, fieldName, value);
            return;
        }
        if (!allowedValues.contains(normalized)) {
            violations.add(new RoadAddressValidationResult.Violation(
                    INVALID_CODE_VALUE,
                    fieldName,
                    value,
                    codeMessage
            ));
        }
    }

    private void validateApartmentFlag(
            List<RoadAddressValidationResult.Violation> violations,
            String value
    ) {
        String normalized = normalize(value);
        if (normalized == null && importMode == RoadAddressImportMode.FULL) {
            return;
        }
        validateRequiredCode(
                violations,
                "apartment_flag",
                value,
                APARTMENT_FLAGS,
                "공동주택구분은 0 또는 1이어야 합니다"
        );
    }

    private void validateEffectiveDate(
            List<RoadAddressValidationResult.Violation> violations,
            String value
    ) {
        String normalized = normalize(value);
        if (normalized == null && importMode == RoadAddressImportMode.FULL) {
            return;
        }
        if (normalized == null) {
            addRequiredViolation(violations, "effective_date", value);
            return;
        }
        if (!EFFECTIVE_DATE_PATTERN.matcher(normalized).matches()) {
            violations.add(new RoadAddressValidationResult.Violation(
                    INVALID_FIELD_FORMAT,
                    "effective_date",
                    value,
                    "효력발생일은 yyyyMMdd 형식의 8자리 숫자여야 합니다"
            ));
            return;
        }

        try {
            LocalDate.parse(normalized, BASIC_DATE_FORMATTER);
        }
        catch (DateTimeParseException exception) {
            violations.add(new RoadAddressValidationResult.Violation(
                    INVALID_CALENDAR_DATE,
                    "effective_date",
                    value,
                    "효력발생일은 실제 존재하는 날짜여야 합니다"
            ));
        }
    }

    private void validateMovementReasonCode(
            List<RoadAddressValidationResult.Violation> violations,
            String value
    ) {
        String normalized = normalize(value);
        if (importMode == RoadAddressImportMode.FULL) {
            if (normalized == null || "31".equals(normalized)) {
                return;
            }
            violations.add(new RoadAddressValidationResult.Violation(
                    INVALID_CODE_VALUE,
                    "movement_reason_code",
                    value,
                    "FULL 적재의 이동사유코드는 비어 있거나 31이어야 합니다"
            ));
            return;
        }

        validateRequiredCode(
                violations,
                "movement_reason_code",
                value,
                MOVEMENT_REASON_CODES,
                "이동사유코드는 31, 34, 63 중 하나여야 합니다"
        );
    }

    private void validateLength(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String originalValue,
            String normalizedValue,
            int maxLength,
            String displayName
    ) {
        int length = normalizedValue.codePointCount(0, normalizedValue.length());
        if (length > maxLength) {
            violations.add(new RoadAddressValidationResult.Violation(
                    FIELD_LENGTH_EXCEEDED,
                    fieldName,
                    originalValue,
                    displayName + "은 최대 " + maxLength + "자까지 허용합니다"
            ));
        }
    }

    private void addRequiredViolation(
            List<RoadAddressValidationResult.Violation> violations,
            String fieldName,
            String value
    ) {
        violations.add(new RoadAddressValidationResult.Violation(
                REQUIRED_FIELD_MISSING,
                fieldName,
                value,
                "필수값이 비어 있습니다"
        ));
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
