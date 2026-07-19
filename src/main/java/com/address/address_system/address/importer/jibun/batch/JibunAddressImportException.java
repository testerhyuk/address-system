package com.address.address_system.address.importer.jibun.batch;

public class JibunAddressImportException extends RuntimeException {

    private final FailureCode failureCode;

    public JibunAddressImportException(FailureCode failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public JibunAddressImportException(
            FailureCode failureCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    public FailureCode getFailureCode() {
        return failureCode;
    }

    public enum FailureCode {
        FILE_NOT_CONFIGURED,
        FILE_NOT_READABLE,
        INVALID_FILE_NAME,
        INVALID_HEADER,
        DUPLICATE_SOURCE_FILE,
        SKIP_LIMIT_EXCEEDED,
        VALIDATION_INCOMPLETE,
        APPLY_PRECONDITION_FAILED,
        APPLY_INCOMPLETE,
        IMPORT_JOB_FAILED
    }
}
