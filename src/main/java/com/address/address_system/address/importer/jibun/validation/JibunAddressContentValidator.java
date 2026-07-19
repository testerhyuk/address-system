package com.address.address_system.address.importer.jibun.validation;

import com.address.address_system.address.importer.jibun.model.JibunAddressImportRecord;
import com.address.address_system.address.importer.jibun.model.JibunAddressValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.batch.infrastructure.item.ItemProcessor;

public class JibunAddressContentValidator implements ItemProcessor<
        JibunAddressImportRecord.Staging,
        JibunAddressValidationResult
        > {

    static final String REQUIRED_FIELD_MISSING = "REQUIRED_FIELD_MISSING";
    static final String INVALID_FIELD_FORMAT = "INVALID_FIELD_FORMAT";
    static final String FIELD_LENGTH_EXCEEDED = "FIELD_LENGTH_EXCEEDED";

    private static final Pattern MANAGEMENT_NUMBER_PATTERN = Pattern.compile("\\d{26}");
    private static final Pattern JIBUN_NUMBER_PATTERN = Pattern.compile("\\d{1,4}");

    @Override
    public JibunAddressValidationResult process(JibunAddressImportRecord.Staging row) {
        List<JibunAddressValidationResult.Violation> violations = new ArrayList<>();

        validateRequiredPattern(
                row.mgmtNum(),
                "mgmt_num",
                MANAGEMENT_NUMBER_PATTERN,
                "관리번호는 숫자 26자리여야 합니다",
                violations
        );
        validateRequiredText(row.bDongName(), "b_dong_name", 40, violations);
        validateOptionalText(row.riName(), "ri_name", 40, violations);
        validateRequiredPattern(
                row.jibunMain(),
                "jibun_main",
                JIBUN_NUMBER_PATTERN,
                "지번 본번은 0부터 9999까지의 숫자여야 합니다",
                violations
        );
        validateRequiredPattern(
                row.jibunSub(),
                "jibun_sub",
                JIBUN_NUMBER_PATTERN,
                "지번 부번은 0부터 9999까지의 숫자여야 합니다",
                violations
        );

        return new JibunAddressValidationResult(row, violations);
    }

    private void validateRequiredPattern(
            String value,
            String fieldName,
            Pattern pattern,
            String detail,
            List<JibunAddressValidationResult.Violation> violations
    ) {
        String normalized = normalize(value);
        if (normalized == null) {
            violations.add(new JibunAddressValidationResult.Violation(
                    REQUIRED_FIELD_MISSING,
                    fieldName,
                    value,
                    fieldName + " 값은 필수입니다"
            ));
            return;
        }
        if (!pattern.matcher(normalized).matches()) {
            violations.add(new JibunAddressValidationResult.Violation(
                    INVALID_FIELD_FORMAT,
                    fieldName,
                    value,
                    detail
            ));
        }
    }

    private void validateRequiredText(
            String value,
            String fieldName,
            int maxLength,
            List<JibunAddressValidationResult.Violation> violations
    ) {
        String normalized = normalize(value);
        if (normalized == null) {
            violations.add(new JibunAddressValidationResult.Violation(
                    REQUIRED_FIELD_MISSING,
                    fieldName,
                    value,
                    fieldName + " 값은 필수입니다"
            ));
            return;
        }
        validateLength(value, normalized, fieldName, maxLength, violations);
    }

    private void validateOptionalText(
            String value,
            String fieldName,
            int maxLength,
            List<JibunAddressValidationResult.Violation> violations
    ) {
        String normalized = normalize(value);
        if (normalized != null) {
            validateLength(value, normalized, fieldName, maxLength, violations);
        }
    }

    private void validateLength(
            String rawValue,
            String normalizedValue,
            String fieldName,
            int maxLength,
            List<JibunAddressValidationResult.Violation> violations
    ) {
        if (normalizedValue.length() > maxLength) {
            violations.add(new JibunAddressValidationResult.Violation(
                    FIELD_LENGTH_EXCEEDED,
                    fieldName,
                    rawValue,
                    fieldName + " 값은 " + maxLength + "자를 초과할 수 없습니다"
            ));
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
