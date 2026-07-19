package com.address.address_system.address.geocoding.client;

public class NominatimClientException extends RuntimeException {

    private final String reasonCode;
    private final boolean retryable;

    public NominatimClientException(
            String reasonCode,
            boolean retryable,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reasonCode = reasonCode;
        this.retryable = retryable;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
