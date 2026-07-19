package com.address.address_system.address.importer.road.source;

import com.address.address_system.address.importer.road.model.RoadAddressImportRecord;
import com.address.address_system.address.importer.road.model.RoadAddressRejectedRow;
import com.address.address_system.address.importer.road.model.RoadAddressStagingRow;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.batch.infrastructure.item.file.LineMapper;

public class RoadAddressCsvLineMapper implements LineMapper<RoadAddressImportRecord> {

    private static final int MAX_REJECTED_VALUE_LENGTH = 4_000;
    private static final int MAX_REASON_DETAIL_LENGTH = 500;

    private final UUID batchId;
    private final RoadAddressCsvFormat.Schema schema;

    public RoadAddressCsvLineMapper(UUID batchId, RoadAddressCsvFormat.Schema schema) {
        this.batchId = batchId;
        this.schema = schema;
    }

    @Override
    public RoadAddressImportRecord mapLine(String line, int lineNumber) {
        try {
            List<String> values = RoadAddressCsvFormat.parseSingleRecord(line);
            if (values.size() != schema.header().size()) {
                return rejected(
                        lineNumber,
                        "CSV_COLUMN_COUNT_MISMATCH",
                        line,
                        "예상 컬럼 수는 " + schema.header().size()
                                + "개지만 실제 컬럼 수는 " + values.size() + "개입니다"
                );
            }

            return switch (schema) {
                case SNAPSHOT -> mapSnapshot(values, lineNumber);
                case CHANGE -> mapChange(values, lineNumber);
            };
        }
        catch (IOException exception) {
            return rejected(
                    lineNumber,
                    "CSV_PARSE_ERROR",
                    line,
                    exception.getMessage()
            );
        }
    }

    private RoadAddressStagingRow mapSnapshot(List<String> values, int lineNumber) {
        Optional<RoadAddressManagementNumber> managementNumber =
                RoadAddressManagementNumber.parse(values.get(0));

        return new RoadAddressStagingRow(
                batchId,
                lineNumber,
                values.get(0),
                managementNumber.map(RoadAddressManagementNumber::legalAreaCode).orElse(null),
                null,
                values.get(1),
                values.get(2),
                values.get(3),
                managementNumber.map(RoadAddressManagementNumber::roadCode).orElse(null),
                values.get(4),
                managementNumber.map(RoadAddressManagementNumber::undergroundFlag).orElse(null),
                values.get(5),
                values.get(6),
                normalizeSnapshotZipCode(values.get(7)),
                null,
                null,
                null,
                values.get(8),
                values.get(9)
        );
    }

    private String normalizeSnapshotZipCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.matches("\\d{4}")) {
            return "0" + normalized;
        }
        return value;
    }

    private RoadAddressStagingRow mapChange(List<String> values, int lineNumber) {
        String legalAreaCode = RoadAddressManagementNumber.parse(values.get(0))
                .map(RoadAddressManagementNumber::legalAreaCode)
                .orElse(null);

        return new RoadAddressStagingRow(
                batchId,
                lineNumber,
                values.get(0),
                legalAreaCode,
                values.get(1),
                values.get(2),
                values.get(3),
                values.get(4),
                values.get(5),
                values.get(6),
                values.get(7),
                values.get(8),
                values.get(9),
                values.get(10),
                values.get(11),
                values.get(12),
                values.get(13),
                values.get(14),
                values.get(15)
        );
    }

    private RoadAddressRejectedRow rejected(
            int lineNumber,
            String reasonCode,
            String rejectedValue,
            String reasonDetail
    ) {
        return new RoadAddressRejectedRow(
                batchId,
                lineNumber,
                reasonCode,
                null,
                truncate(rejectedValue, MAX_REJECTED_VALUE_LENGTH),
                truncate(reasonDetail, MAX_REASON_DETAIL_LENGTH)
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
