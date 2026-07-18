package com.address.address_system.address.importer.road;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class RoadAddressCsvLineMapperTest {

    private final UUID batchId = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private final RoadAddressCsvLineMapper mapper = new RoadAddressCsvLineMapper(batchId);

    @Test
    void mapsKoreanAndQuotedCommaWithoutLosingEmptyValues() {
        String line = "26110101300600100000100002,2611010100,부산광역시,중구,영주동,"
                + "261102000001,초량상로,0,1,2,48910,20260718,1,31,\"공식,건물명\",";

        RoadAddressImportRecord result = mapper.mapLine(line, 2);

        assertThat(result).isInstanceOfSatisfying(RoadAddressStagingRow.class, row -> {
            assertThat(row.batchId()).isEqualTo(batchId);
            assertThat(row.sourceRowNumber()).isEqualTo(2);
            assertThat(row.mgmtNum()).isEqualTo("26110101300600100000100002");
            assertThat(row.legalDongCode()).isEqualTo("2611010100");
            assertThat(row.sido()).isEqualTo("부산광역시");
            assertThat(row.roadCode()).isEqualTo("261102000001");
            assertThat(row.undergroundFlag()).isEqualTo("0");
            assertThat(row.effectiveDate()).isEqualTo("20260718");
            assertThat(row.apartmentFlag()).isEqualTo("1");
            assertThat(row.movementReasonCode()).isEqualTo("31");
            assertThat(row.buildNameOfficial()).isEqualTo("공식,건물명");
            assertThat(row.buildNameSigungu()).isEmpty();
        });
    }

    @Test
    void rejectsWrongColumnCount() {
        String line = "1,2611010100,부산광역시,중구,영주동,261102000001,초량상로,0,"
                + "1,2,48910,20260718,1,31,건물명";

        RoadAddressImportRecord result = mapper.mapLine(line, 3);

        assertThat(result).isInstanceOfSatisfying(RoadAddressRejectedRow.class, row -> {
            assertThat(row.reasonCode()).isEqualTo("CSV_COLUMN_COUNT_MISMATCH");
            assertThat(row.sourceRowNumber()).isEqualTo(3);
        });
    }

    @Test
    void rejectsMalformedCsv() {
        String line = "1,2611010100,부산광역시,중구,영주동,261102000001,초량상로,0,"
                + "1,2,48910,20260718,1,31,\"닫히지 않은 값,건물명,";

        RoadAddressImportRecord result = mapper.mapLine(line, 4);

        assertThat(result).isInstanceOfSatisfying(RoadAddressRejectedRow.class, row ->
                assertThat(row.reasonCode()).isEqualTo("CSV_PARSE_ERROR")
        );
    }
}
