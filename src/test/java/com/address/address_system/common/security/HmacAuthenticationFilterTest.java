package com.address.address_system.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.address.address_system.common.security.HmacSignatureVerifier.VerifiedRequest;
import com.address.address_system.common.security.ApiRateLimitService.Decision;
import com.address.address_system.common.security.ApiSecurityProperties.ApiClientRole;

import jakarta.servlet.FilterChain;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class HmacAuthenticationFilterTest {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final UUID REQUEST_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000702");

    private final HmacSignatureVerifier verifier = mock(HmacSignatureVerifier.class);
    private final ReplayProtectionRepository replayRepository =
            mock(ReplayProtectionRepository.class);
    private final ApiRateLimitService rateLimitService = mock(ApiRateLimitService.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesAndMakesBodyAvailableToController() throws Exception {
        byte[] body = "{\"buildingDong\":\"101동\"}"
                .getBytes(StandardCharsets.UTF_8);
        HmacAuthenticationFilter filter = filter(65_536);
        MockHttpServletRequest request = apiRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        VerifiedRequest verified = new VerifiedRequest(
                "delivery-system",
                REQUEST_ID,
                NOW,
                ApiClientRole.DELIVERY
        );
        when(verifier.verify(any(), eq(body))).thenReturn(verified);
        when(replayRepository.claim(
                eq("delivery-system"),
                eq(REQUEST_ID),
                eq(NOW),
                any()
        )).thenReturn(true);
        when(rateLimitService.consume(any(), any(), any()))
                .thenReturn(Decision.allowed(1000, 999));
        AtomicReference<String> controllerBody = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> {
            controllerBody.set(new String(
                    servletRequest.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            ((MockHttpServletResponse) servletResponse).setStatus(204);
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(controllerBody).hasValue(new String(body, StandardCharsets.UTF_8));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("delivery-system");
    }

    @Test
    void grantsAdminRoleOnlyToVerifiedAdminClient() throws Exception {
        byte[] body = new byte[0];
        HmacAuthenticationFilter filter = filter(65_536);
        MockHttpServletRequest request = apiRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(verifier.verify(any(), eq(body))).thenReturn(new VerifiedRequest(
                "address-operator",
                REQUEST_ID,
                NOW,
                ApiClientRole.ADMIN
        ));
        when(replayRepository.claim(any(), any(), any(), any())).thenReturn(true);
        when(rateLimitService.consume(any(), any(), any()))
                .thenReturn(Decision.allowed(200, 199));
        AtomicReference<String> authority = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> authority.set(
                SecurityContextHolder.getContext().getAuthentication()
                        .getAuthorities().iterator().next().getAuthority()
        );

        filter.doFilter(request, response, chain);

        assertThat(authority).hasValue("ROLE_ADMIN");
    }

    @Test
    void rejectsAlreadyClaimedRequestId() throws Exception {
        byte[] body = new byte[0];
        HmacAuthenticationFilter filter = filter(65_536);
        MockHttpServletRequest request = apiRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(verifier.verify(any(), eq(body))).thenReturn(new VerifiedRequest(
                "delivery-system",
                REQUEST_ID,
                NOW,
                ApiClientRole.DELIVERY
        ));
        when(replayRepository.claim(any(), any(), any(), any())).thenReturn(false);

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("replayed request must not reach controller");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void rejectsBodyBeforeAuthenticationWhenItExceedsLimit() throws Exception {
        HmacAuthenticationFilter filter = filter(4);
        MockHttpServletRequest request = apiRequest("12345".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("oversized request must not reach controller");
        });

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        verifyNoInteractions(verifier, replayRepository, rateLimitService);
    }

    @Test
    void failsClosedWhenReplayStoreIsUnavailable() throws Exception {
        byte[] body = new byte[0];
        HmacAuthenticationFilter filter = filter(65_536);
        MockHttpServletRequest request = apiRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(verifier.verify(any(), eq(body))).thenReturn(new VerifiedRequest(
                "delivery-system",
                REQUEST_ID,
                NOW,
                ApiClientRole.DELIVERY
        ));
        when(replayRepository.claim(any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("request must fail closed");
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_UNAVAILABLE");
    }

    @Test
    void returnsTooManyRequestsWithRetryAfterWhenBucketIsEmpty() throws Exception {
        byte[] body = new byte[0];
        HmacAuthenticationFilter filter = filter(65_536);
        MockHttpServletRequest request = apiRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(verifier.verify(any(), eq(body))).thenReturn(new VerifiedRequest(
                "delivery-system",
                REQUEST_ID,
                NOW,
                ApiClientRole.DELIVERY
        ));
        when(replayRepository.claim(any(), any(), any(), any())).thenReturn(true);
        when(rateLimitService.consume(any(), any(), any()))
                .thenReturn(Decision.rejected(500, 0, 2));

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            throw new AssertionError("rate-limited request must not reach controller");
        });

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("500");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    private HmacAuthenticationFilter filter(int maximumBodyBytes) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new HmacAuthenticationFilter(
                verifier,
                replayRepository,
                rateLimitService,
                new ApiAuthenticationFailureWriter(
                        JsonMapper.builder().findAndAddModules().build(),
                        clock
                ),
                HmacSignatureVerifierTest.properties(maximumBodyBytes),
                clock
        );
    }

    private MockHttpServletRequest apiRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/v1/delivery-targets"
        );
        request.setContent(body);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContentType("application/json");
        return request;
    }
}
