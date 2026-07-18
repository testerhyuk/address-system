package com.address.address_system.address.importer.road;

import java.io.IOException;
import java.util.List;

public class RoadAddressCsvHeaderValidator {

    public RoadAddressCsvFormat.Schema detect(String headerLine) {
        String normalizedHeader = removeUtf8Bom(headerLine);

        try {
            List<String> actualHeader = RoadAddressCsvFormat.parseSingleRecord(normalizedHeader);
            for (RoadAddressCsvFormat.Schema schema : RoadAddressCsvFormat.Schema.values()) {
                if (schema.header().equals(actualHeader)) {
                    return schema;
                }
            }
            throw invalidHeader("도로명주소 CSV 헤더의 컬럼 또는 순서가 지원 형식과 다릅니다");
        }
        catch (IOException exception) {
            throw new RoadAddressImportException(
                    RoadAddressImportFailureCode.INVALID_HEADER,
                    "도로명주소 CSV 헤더를 해석할 수 없습니다",
                    exception
            );
        }
    }

    public void validate(String headerLine, RoadAddressCsvFormat.Schema expectedSchema) {
        RoadAddressCsvFormat.Schema actualSchema = detect(headerLine);
        if (actualSchema != expectedSchema) {
            throw invalidHeader("파일 검사 때 확인한 CSV 형식과 실제 읽기 형식이 다릅니다");
        }
    }

    private String removeUtf8Bom(String value) {
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private RoadAddressImportException invalidHeader(String message) {
        return new RoadAddressImportException(
                RoadAddressImportFailureCode.INVALID_HEADER,
                message
        );
    }
}
