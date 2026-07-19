package com.address.address_system.address.importer.jibun.model;

import java.util.UUID;

public sealed interface JibunAddressImportRecord permits
        JibunAddressImportRecord.Staging,
        JibunAddressImportRecord.Rejected {

    UUID batchId();

    long sourceRowNumber();

    record Staging(
            UUID batchId,
            long sourceRowNumber,
            String mgmtNum,
            String bDongName,
            String riName,
            String jibunMain,
            String jibunSub
    ) implements JibunAddressImportRecord {
    }

    record Rejected(
            UUID batchId,
            long sourceRowNumber,
            String reasonCode,
            String fieldName,
            String rejectedValue,
            String reasonDetail
    ) implements JibunAddressImportRecord {
    }
}
