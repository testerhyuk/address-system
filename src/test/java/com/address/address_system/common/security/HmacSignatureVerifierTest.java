package com.address.address_system.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.address.address_system.common.security.HmacSignatureVerifier.InvalidHmacRequestException;
import com.address.address_system.common.security.HmacSignatureVerifier.VerifiedRequest;
import com.address.address_system.common.security.ApiSecurityProperties.ApiClientRole;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HmacSignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final String CLIENT_ID = "delivery-system";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ADMIN_CLIENT_ID = "address-operator";
    private static final String ADMIN_SECRET = "abcdef0123456789abcdef0123456789";
    private static final UUID REQUEST_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000701");

    private final ApiSecurityProperties properties = properties(65_536);
    private final HmacSignatureVerifier verifier = new HmacSignatureVerifier(
            properties,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void acceptsValidSignature() throws Exception {
        byte[] body = "{\"roadAddressId\":\"example\"}"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(NOW, REQUEST_ID, body);

        VerifiedRequest verified = verifier.verify(request, body);

        assertThat(verified.clientId()).isEqualTo(CLIENT_ID);
        assertThat(verified.requestId()).isEqualTo(REQUEST_ID);
        assertThat(verified.requestTimestamp()).isEqualTo(NOW);
        assertThat(verified.role()).isEqualTo(ApiClientRole.DELIVERY);
    }

    @Test
    void acceptsAdminSignatureWithAdminRole() throws Exception {
        byte[] body = "{\"reason\":\"reviewed\"}"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(
                NOW,
                REQUEST_ID,
                body,
                ADMIN_CLIENT_ID,
                ADMIN_SECRET,
                "/api/v1/admin/coordinate-candidates/example/approve"
        );

        VerifiedRequest verified = verifier.verify(request, body);

        assertThat(verified.clientId()).isEqualTo(ADMIN_CLIENT_ID);
        assertThat(verified.role()).isEqualTo(ApiClientRole.ADMIN);
    }

    @Test
    void rejectsRequestWhenBodyWasChangedAfterSigning() throws Exception {
        byte[] signedBody = "{\"buildingDong\":\"101동\"}"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = signedRequest(NOW, REQUEST_ID, signedBody);
        byte[] changedBody = "{\"buildingDong\":\"102동\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify(request, changedBody))
                .isInstanceOf(InvalidHmacRequestException.class);
    }

    @Test
    void rejectsRequestOutsideAllowedClockSkew() throws Exception {
        Instant expired = NOW.minus(Duration.ofMinutes(5).plusSeconds(1));
        byte[] body = new byte[0];
        MockHttpServletRequest request = signedRequest(expired, REQUEST_ID, body);

        assertThatThrownBy(() -> verifier.verify(request, body))
                .isInstanceOf(InvalidHmacRequestException.class);
    }

    @Test
    void rejectsUnknownClientWithoutRevealingWhichHeaderFailed() throws Exception {
        byte[] body = new byte[0];
        MockHttpServletRequest request = signedRequest(NOW, REQUEST_ID, body);
        request.removeHeader(HmacSignatureVerifier.CLIENT_ID_HEADER);
        request.addHeader(HmacSignatureVerifier.CLIENT_ID_HEADER, "unknown-client");

        assertThatThrownBy(() -> verifier.verify(request, body))
                .isInstanceOf(InvalidHmacRequestException.class)
                .hasMessage(null);
    }

    @Test
    void rejectsRequestIdThatIsNotRandomUuidVersionFour() throws Exception {
        UUID nonRandomRequestId =
                UUID.fromString("00000000-0000-0000-0000-000000000701");
        byte[] body = new byte[0];
        MockHttpServletRequest request = signedRequest(NOW, nonRandomRequestId, body);

        assertThatThrownBy(() -> verifier.verify(request, body))
                .isInstanceOf(InvalidHmacRequestException.class);
    }

    private MockHttpServletRequest signedRequest(
            Instant timestamp,
            UUID requestId,
            byte[] body
    ) throws Exception {
        return signedRequest(
                timestamp,
                requestId,
                body,
                CLIENT_ID,
                SECRET,
                "/api/v1/delivery-targets"
        );
    }

    private MockHttpServletRequest signedRequest(
            Instant timestamp,
            UUID requestId,
            byte[] body,
            String clientId,
            String secret,
            String path
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                path
        );
        String timestampValue = timestamp.toString();
        String requestIdValue = requestId.toString();
        request.addHeader(HmacSignatureVerifier.CLIENT_ID_HEADER, clientId);
        request.addHeader(HmacSignatureVerifier.TIMESTAMP_HEADER, timestampValue);
        request.addHeader(HmacSignatureVerifier.REQUEST_ID_HEADER, requestIdValue);
        request.addHeader(
                HmacSignatureVerifier.SIGNATURE_HEADER,
                signature(
                        "POST",
                        path,
                        timestampValue,
                        requestIdValue,
                        body,
                        secret
                )
        );
        return request;
    }

    private String signature(
            String method,
            String target,
            String timestamp,
            String requestId,
            byte[] body,
            String secret
    ) throws Exception {
        String bodyHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(body)
        );
        String canonical = String.join(
                "\n",
                method,
                target,
                timestamp,
                requestId,
                bodyHash
        );
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        ));
        return Base64.getEncoder().encodeToString(
                mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))
        );
    }

    static ApiSecurityProperties properties(int maximumBodyBytes) {
        return new ApiSecurityProperties(
                true,
                CLIENT_ID,
                SECRET,
                ADMIN_CLIENT_ID,
                ADMIN_SECRET,
                Duration.ofMinutes(5),
                Duration.ofMinutes(15),
                Duration.ofMinutes(10),
                maximumBodyBytes
        );
    }
}
