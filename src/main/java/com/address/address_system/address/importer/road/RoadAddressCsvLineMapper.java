package com.address.address_system.address.importer.road;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.batch.infrastructure.item.file.LineMapper;

public class RoadAddressCsvLineMapper implements LineMapper<RoadAddressImportRecord> {

    private static final int MAX_REJECTED_VALUE_LENGTH = 4_000;
    private static final int MAX_REASON_DETAIL_LENGTH = 500;

    private final UUID batchId;

    public RoadAddressCsvLineMapper(UUID batchId) {
        this.batchId = batchId;
    }

    @Override
    public RoadAddressImportRecord mapLine(String line, int lineNumber) {
        try {
            List<String> values = RoadAddressCsvFormat.parseSingleRecord(line);
            if (values.size() != RoadAddressCsvFormat.EXPECTED_HEADER.size()) {
                return rejected(
                        lineNumber,
                        "CSV_COLUMN_COUNT_MISMATCH",
                        line,
                        "예상 컬럼 수는 " + RoadAddressCsvFormat.EXPECTED_HEADER.size()
                                + "개이지만 실제 컬럼 수는 " + values.size() + "개입니다"
                );
            }

            return new RoadAddressStagingRow(
                    batchId,
                    lineNumber,
                    values.get(0),
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
        catch (IOException exception) {
            return rejected(
                    lineNumber,
                    "CSV_PARSE_ERROR",
                    line,
                    exception.getMessage()
            );
        }
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
