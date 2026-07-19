package com.address.address_system.address.importer.jibun.source;

import com.address.address_system.address.importer.jibun.model.JibunAddressImportRecord;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JibunAddressCsvLineMapperTest {

    private static final UUID BATCH_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000801");

    private final JibunAddressCsvLineMapper mapper = new JibunAddressCsvLineMapper(BATCH_ID);

    @Test
    void mapsFiveColumnsToStagingRow() {
        JibunAddressImportRecord mapped = mapper.mapLine(
                "26110110200001000000200000,중앙동7가,,81,2",
                2
        );

        assertThat(mapped).isEqualTo(new JibunAddressImportRecord.Staging(
                BATCH_ID,
                2,
                "26110110200001000000200000",
                "중앙동7가",
                "",
                "81",
                "2"
        ));
    }

    @Test
    void convertsWrongColumnCountToRejectedRecord() {
        JibunAddressImportRecord mapped = mapper.mapLine(
                "26110110200001000000200000,중앙동7가,81,2",
                3
        );

        assertThat(mapped).isInstanceOfSatisfying(
                JibunAddressImportRecord.Rejected.class,
                rejected -> assertThat(rejected.reasonCode())
                        .isEqualTo("CSV_COLUMN_COUNT_MISMATCH")
        );
    }
}
