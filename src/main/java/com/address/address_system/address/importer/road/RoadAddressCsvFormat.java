package com.address.address_system.address.importer.road;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

final class RoadAddressCsvFormat {

    static final List<String> SNAPSHOT_HEADER = List.of(
            "mgmt_num",
            "sido",
            "sigungu",
            "b_dong_name",
            "road_name",
            "build_main",
            "build_sub",
            "zip_code",
            "build_nm_official",
            "build_nm_sgg"
    );

    static final List<String> CHANGE_HEADER = List.of(
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
                    throw new IOException("CSV 한 줄에는 하나의 레코드만 허용합니다");
                }
                return records.get(0).toList();
            }
        }
        catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    enum Schema {
        SNAPSHOT(SNAPSHOT_HEADER),
        CHANGE(CHANGE_HEADER);

        private final List<String> header;

        Schema(List<String> header) {
            this.header = header;
        }

        List<String> header() {
            return header;
        }
    }
}
