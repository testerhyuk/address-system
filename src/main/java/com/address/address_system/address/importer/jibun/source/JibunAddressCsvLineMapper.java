package com.address.address_system.address.importer.jibun.source;

import com.address.address_system.address.importer.jibun.model.JibunAddressImportRecord;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.batch.infrastructure.item.file.LineMapper;

public class JibunAddressCsvLineMapper implements LineMapper<JibunAddressImportRecord> {

    private static final int MAX_REJECTED_VALUE_LENGTH = 4_000;
    private static final int MAX_REASON_DETAIL_LENGTH = 500;

    private final UUID batchId;

    public JibunAddressCsvLineMapper(UUID batchId) {
        this.batchId = batchId;
    }

    @Override
    public JibunAddressImportRecord mapLine(String line, int lineNumber) {
        try {
            List<String> values = JibunAddressCsvFormat.parseSingleRecord(line);
            if (values.size() != JibunAddressCsvFormat.HEADER.size()) {
                return rejected(
                        lineNumber,
                        "CSV_COLUMN_COUNT_MISMATCH",
                        line,
                        "예상 컬럼 수는 5개지만 실제 컬럼 수는 " + values.size() + "개입니다"
                );
            }

            return new JibunAddressImportRecord.Staging(
                    batchId,
                    lineNumber,
                    values.get(0),
                    values.get(1),
                    values.get(2),
                    values.get(3),
                    values.get(4)
            );
        }
        catch (IOException exception) {
            return rejected(lineNumber, "CSV_PARSE_ERROR", line, exception.getMessage());
        }
    }

    private JibunAddressImportRecord.Rejected rejected(
            int lineNumber,
            String reasonCode,
            String rejectedValue,
            String reasonDetail
    ) {
        return new JibunAddressImportRecord.Rejected(
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
