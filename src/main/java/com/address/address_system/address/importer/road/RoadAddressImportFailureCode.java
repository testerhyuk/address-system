package com.address.address_system.address.importer.road;

public enum RoadAddressImportFailureCode {
    FILE_NOT_CONFIGURED,
    FILE_NOT_READABLE,
    INVALID_FILE_NAME,
    INVALID_HEADER,
    DUPLICATE_SOURCE_FILE,
    SKIP_LIMIT_EXCEEDED,
    VALIDATION_INCOMPLETE,
    VALIDATION_STATE_CONFLICT,
    IMPORT_JOB_FAILED
}
