package com.address.address_system.address.importer.jibun.source;

import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException;
import com.address.address_system.address.importer.jibun.batch.JibunAddressImportException.FailureCode;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class JibunAddressCsvFormat {

    public static final List<String> HEADER = List.of(
            "mgmt_num",
            "b_dong_name",
            "ri_name",
            "jibun_main",
            "jibun_sub"
    );

    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
            .setIgnoreEmptyLines(false)
            .get();

    private JibunAddressCsvFormat() {
    }

    public static void validateHeader(String headerLine) {
        String normalized = removeUtf8Bom(headerLine);
        try {
            List<String> actualHeader = parseSingleRecord(normalized);
            if (!HEADER.equals(actualHeader)) {
                throw invalidHeader("지번 CSV 헤더의 컬럼 또는 순서가 지원 형식과 다릅니다");
            }
        }
        catch (IOException exception) {
            throw new JibunAddressImportException(
                    FailureCode.INVALID_HEADER,
                    "지번 CSV 헤더를 해석할 수 없습니다",
                    exception
            );
        }
    }

    public static List<String> parseSingleRecord(String line) throws IOException {
        try {
            try (CSVParser parser = FORMAT.parse(new StringReader(line))) {
                List<CSVRecord> records = parser.getRecords();
                if (records.size() != 1) {
                    throw new IOException("CSV 한 줄에는 하나의 레코드만 허용됩니다");
                }
                return records.get(0).toList();
            }
        }
        catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static String removeUtf8Bom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static JibunAddressImportException invalidHeader(String message) {
        return new JibunAddressImportException(FailureCode.INVALID_HEADER, message);
    }
}
