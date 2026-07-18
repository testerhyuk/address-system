package com.address.address_system.address.importer.road;

import java.io.IOException;
import java.util.List;

public class RoadAddressCsvHeaderValidator {

    public void validate(String headerLine) {
        String normalizedHeader = removeUtf8Bom(headerLine);

        try {
            List<String> actualHeader = RoadAddressCsvFormat.parseSingleRecord(normalizedHeader);
            if (!RoadAddressCsvFormat.EXPECTED_HEADER.equals(actualHeader)) {
                throw invalidHeader("도로명주소 CSV 헤더가 예상 컬럼 또는 순서와 다릅니다");
            }
        }
        catch (IOException exception) {
            throw new RoadAddressImportException(
                    RoadAddressImportFailureCode.INVALID_HEADER,
                    "도로명주소 CSV 헤더를 해석할 수 없습니다",
                    exception
            );
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
