package com.address.address_system.address.importer.road;

public class RoadAddressImportException extends RuntimeException {

    private final RoadAddressImportFailureCode failureCode;

    public RoadAddressImportException(RoadAddressImportFailureCode failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public RoadAddressImportException(
            RoadAddressImportFailureCode failureCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failureCode = failureCode;
    }

    public RoadAddressImportFailureCode getFailureCode() {
        return failureCode;
    }
}
