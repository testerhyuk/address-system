package com.address.address_system.common.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.address.address_system.common.security.ApiSecurityProperties.ApiClientRole;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "address.api-security",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class HmacSignatureVerifier {

    public static final String CLIENT_ID_HEADER = "X-Address-Client-Id";
    public static final String TIMESTAMP_HEADER = "X-Address-Timestamp";
    public static final String REQUEST_ID_HEADER = "X-Address-Request-Id";
    public static final String SIGNATURE_HEADER = "X-Address-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ApiSecurityProperties properties;
    private final Clock clock;

    public HmacSignatureVerifier(ApiSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public VerifiedRequest verify(HttpServletRequest request, byte[] body) {
        String clientId = requiredHeader(request, CLIENT_ID_HEADER);
        String timestampValue = requiredHeader(request, TIMESTAMP_HEADER);
        String requestIdValue = requiredHeader(request, REQUEST_ID_HEADER);
        String signatureValue = requiredHeader(request, SIGNATURE_HEADER);

        ApiClientRole role = resolveRole(clientId);

        Instant requestTimestamp = parseTimestamp(timestampValue);
        verifyTimestamp(requestTimestamp);
        UUID requestId = parseRequestId(requestIdValue);

        byte[] suppliedSignature = decodeSignature(signatureValue);
        byte[] expectedSignature = calculateSignature(
                request.getMethod(),
                requestTarget(request),
                timestampValue,
                requestIdValue,
                body,
                properties.clientSecretBytes(role)
        );
        if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
            throw new InvalidHmacRequestException();
        }

        return new VerifiedRequest(clientId, requestId, requestTimestamp, role);
    }

    private ApiClientRole resolveRole(String clientId) {
        byte[] supplied = clientId.getBytes(StandardCharsets.UTF_8);
        boolean deliveryClient = MessageDigest.isEqual(
                supplied,
                properties.clientId().getBytes(StandardCharsets.UTF_8)
        );
        boolean adminClient = MessageDigest.isEqual(
                supplied,
                properties.adminClientId().getBytes(StandardCharsets.UTF_8)
        );
        if (!deliveryClient && !adminClient) {
            throw new InvalidHmacRequestException();
        }
        return adminClient ? ApiClientRole.ADMIN : ApiClientRole.DELIVERY;
    }

    private String requiredHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new InvalidHmacRequestException();
        }
        return value;
    }

    private Instant parseTimestamp(String value) {
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException exception) {
            throw new InvalidHmacRequestException();
        }
    }

    private UUID parseRequestId(String value) {
        try {
            UUID requestId = UUID.fromString(value);
            if (requestId.version() != 4 || requestId.variant() != 2) {
                throw new InvalidHmacRequestException();
            }
            return requestId;
        }
        catch (IllegalArgumentException exception) {
            throw new InvalidHmacRequestException();
        }
    }

    private void verifyTimestamp(Instant requestTimestamp) {
        Duration difference = Duration.between(clock.instant(), requestTimestamp).abs();
        if (difference.compareTo(properties.allowedClockSkew()) > 0) {
            throw new InvalidHmacRequestException();
        }
    }

    private byte[] decodeSignature(String value) {
        try {
            byte[] signature = Base64.getDecoder().decode(value);
            if (signature.length != 32) {
                throw new InvalidHmacRequestException();
            }
            return signature;
        }
        catch (IllegalArgumentException exception) {
            throw new InvalidHmacRequestException();
        }
    }

    private byte[] calculateSignature(
            String method,
            String requestTarget,
            String timestamp,
            String requestId,
            byte[] body,
            byte[] clientSecret
    ) {
        String bodyHash = HexFormat.of().formatHex(sha256(body));
        String canonicalRequest = String.join(
                "\n",
                method.toUpperCase(Locale.ROOT),
                requestTarget,
                timestamp,
                requestId,
                bodyHash
        );
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(clientSecret, HMAC_ALGORITHM));
            return mac.doFinal(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        }
        catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String requestTarget(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    public record VerifiedRequest(
            String clientId,
            UUID requestId,
            Instant requestTimestamp,
            ApiClientRole role
    ) {
    }

    public static class InvalidHmacRequestException extends RuntimeException {
    }
}
