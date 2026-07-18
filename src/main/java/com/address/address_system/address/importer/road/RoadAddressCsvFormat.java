package com.address.address_system.address.importer.road;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

final class RoadAddressCsvFormat {

    static final List<String> EXPECTED_HEADER = List.of(
            "mgmt_num",
            "legal_dong_code",
            "sido",
            "sigungu",
            "b_dong_name",
            "road_code",
            "road_name",
            "underground_flag",
            "build_main",
            "build_sub",
            "zip_code",
            "effective_date",
            "apartment_flag",
            "movement_reason_code",
            "build_nm_official",
            "build_nm_sgg"
    );

    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
            .setIgnoreEmptyLines(false)
            .get();

    private RoadAddressCsvFormat() {
    }

    static List<String> parseSingleRecord(String line) throws IOException {
        try {
            try (CSVParser parser = FORMAT.parse(new StringReader(line))) {
                List<CSVRecord> records = parser.getRecords();
                if (records.size() != 1) {
                    throw new IOException("CSV 한 행에서 하나의 레코드만 허용됩니다");
                }
                return records.get(0).toList();
            }
        }
        catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
