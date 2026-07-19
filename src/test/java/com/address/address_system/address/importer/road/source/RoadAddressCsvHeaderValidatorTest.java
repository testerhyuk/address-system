package com.address.address_system.address.importer.road.source;

import com.address.address_system.address.importer.road.batch.RoadAddressImportException;
import com.address.address_system.address.importer.road.batch.RoadAddressImportFailureCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoadAddressCsvHeaderValidatorTest {

    private static final String SNAPSHOT_HEADER =
            String.join(",", RoadAddressCsvFormat.SNAPSHOT_HEADER);
    private static final String CHANGE_HEADER =
            String.join(",", RoadAddressCsvFormat.CHANGE_HEADER);

    private final RoadAddressCsvHeaderValidator validator = new RoadAddressCsvHeaderValidator();

    @Test
    void detectsSnapshotHeader() {
        assertThat(validator.detect(SNAPSHOT_HEADER))
                .isEqualTo(RoadAddressCsvFormat.Schema.SNAPSHOT);
    }

    @Test
    void detectsChangeHeaderWithUtf8Bom() {
        assertThat(validator.detect("\uFEFF" + CHANGE_HEADER))
                .isEqualTo(RoadAddressCsvFormat.Schema.CHANGE);
    }

    @Test
    void rejectsChangedColumnOrder() {
        String changedHeader = SNAPSHOT_HEADER.replace(
                "mgmt_num,sido",
                "sido,mgmt_num"
        );

        assertThatThrownBy(() -> validator.detect(changedHeader))
                .isInstanceOfSatisfying(RoadAddressImportException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(RoadAddressImportFailureCode.INVALID_HEADER)
                );
    }

    @Test
    void rejectsSchemaChangedAfterInspection() {
        assertThatThrownBy(() -> validator.validate(
                CHANGE_HEADER,
                RoadAddressCsvFormat.Schema.SNAPSHOT
        )).isInstanceOf(RoadAddressImportException.class);
    }
}
