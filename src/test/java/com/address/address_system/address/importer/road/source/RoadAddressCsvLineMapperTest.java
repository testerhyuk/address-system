package com.address.address_system.address.importer.road.source;

import com.address.address_system.address.importer.road.model.RoadAddressImportRecord;
import com.address.address_system.address.importer.road.model.RoadAddressRejectedRow;
import com.address.address_system.address.importer.road.model.RoadAddressStagingRow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class RoadAddressCsvLineMapperTest {

    private final UUID batchId = UUID.fromString("00000000-0000-0000-0000-000000000501");

    @Test
    void mapsSnapshotAndDerivesManagementNumberComponents() {
        RoadAddressCsvLineMapper mapper = new RoadAddressCsvLineMapper(
                batchId,
                RoadAddressCsvFormat.Schema.SNAPSHOT
        );
        String line = "26110101300600100000100002,부산광역시,중구,영주동,초량상로,"
                + "1,2,3047,,영주주차장";

        RoadAddressImportRecord result = mapper.mapLine(line, 2);

        assertThat(result).isInstanceOfSatisfying(RoadAddressStagingRow.class, row -> {
            assertThat(row.legalAreaCode()).isEqualTo("26110101");
            assertThat(row.legalDongCode()).isNull();
            assertThat(row.roadCode()).isEqualTo("261103006001");
            assertThat(row.undergroundFlag()).isEqualTo("0");
            assertThat(row.effectiveDate()).isNull();
            assertThat(row.apartmentFlag()).isNull();
            assertThat(row.movementReasonCode()).isNull();
            assertThat(row.buildSub()).isEqualTo("2");
            assertThat(row.zipCode()).isEqualTo("03047");
        });
    }

    @Test
    void mapsChangeRowAndKeepsProvidedChangeFields() {
        RoadAddressCsvLineMapper mapper = new RoadAddressCsvLineMapper(
                batchId,
                RoadAddressCsvFormat.Schema.CHANGE
        );
        String line = "26110101300600100000100002,2611010100,부산광역시,중구,영주동,"
                + "261103006001,초량상로,0,1,2,48910,20260718,1,31,\"공식,건물명\",";

        RoadAddressImportRecord result = mapper.mapLine(line, 2);

        assertThat(result).isInstanceOfSatisfying(RoadAddressStagingRow.class, row -> {
            assertThat(row.legalAreaCode()).isEqualTo("26110101");
            assertThat(row.legalDongCode()).isEqualTo("2611010100");
            assertThat(row.effectiveDate()).isEqualTo("20260718");
            assertThat(row.apartmentFlag()).isEqualTo("1");
            assertThat(row.movementReasonCode()).isEqualTo("31");
            assertThat(row.buildNameOfficial()).isEqualTo("공식,건물명");
            assertThat(row.buildNameSigungu()).isEmpty();
        });
    }

    @Test
    void rejectsWrongColumnCountForDetectedSchema() {
        RoadAddressCsvLineMapper mapper = new RoadAddressCsvLineMapper(
                batchId,
                RoadAddressCsvFormat.Schema.SNAPSHOT
        );

        RoadAddressImportRecord result = mapper.mapLine("1,부산,중구", 3);

        assertThat(result).isInstanceOfSatisfying(RoadAddressRejectedRow.class, row -> {
            assertThat(row.reasonCode()).isEqualTo("CSV_COLUMN_COUNT_MISMATCH");
            assertThat(row.sourceRowNumber()).isEqualTo(3);
        });
    }

    @Test
    void rejectsMalformedCsv() {
        RoadAddressCsvLineMapper mapper = new RoadAddressCsvLineMapper(
                batchId,
                RoadAddressCsvFormat.Schema.SNAPSHOT
        );
        String line = "1,부산,중구,영주동,초량상로,1,2,48910,\"닫히지 않은 건물명,";

        RoadAddressImportRecord result = mapper.mapLine(line, 4);

        assertThat(result).isInstanceOfSatisfying(RoadAddressRejectedRow.class, row ->
                assertThat(row.reasonCode()).isEqualTo("CSV_PARSE_ERROR")
        );
    }
}
