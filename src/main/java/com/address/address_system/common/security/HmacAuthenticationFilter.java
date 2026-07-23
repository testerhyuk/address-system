package com.address.address_system.common.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.address.address_system.common.security.HmacSignatureVerifier.InvalidHmacRequestException;
import com.address.address_system.common.security.HmacSignatureVerifier.VerifiedRequest;
import com.address.address_system.common.security.ApiRateLimitService.Decision;
import com.address.address_system.common.security.ApiSecurityProperties.ApiClientRole;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

@Component
@ConditionalOnProperty(
        prefix = "address.api-security",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class HmacAuthenticationFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PATH_PREFIX = "/api/v1/";

    private final HmacSignatureVerifier verifier;
    private final ReplayProtectionRepository replayProtectionRepository;
    private final ApiRateLimitService rateLimitService;
    private final ApiAuthenticationFailureWriter failureWriter;
    private final ApiSecurityProperties properties;
    private final Clock clock;

    public HmacAuthenticationFilter(
            HmacSignatureVerifier verifier,
            ReplayProtectionRepository replayProtectionRepository,
            ApiRateLimitService rateLimitService,
            ApiAuthenticationFailureWriter failureWriter,
            ApiSecurityProperties properties,
            Clock clock
    ) {
        this.verifier = verifier;
        this.replayProtectionRepository = replayProtectionRepository;
        this.rateLimitService = rateLimitService;
        this.failureWriter = failureWriter;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PROTECTED_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        byte[] body;
        try {
            body = readBody(request);
        }
        catch (RequestBodyTooLargeException exception) {
            failureWriter.payloadTooLarge(response);
            return;
        }

        CachedBodyHttpServletRequest wrappedRequest =
                new CachedBodyHttpServletRequest(request, body);
        try {
            VerifiedRequest verified = verifier.verify(wrappedRequest, body);
            Instant expiresAt = clock.instant().plus(properties.replayRetention());
            if (!replayProtectionRepository.claim(
                    verified.clientId(),
                    verified.requestId(),
                    verified.requestTimestamp(),
                    expiresAt
            )) {
                failureWriter.unauthorized(response);
                return;
            }

            Decision rateLimit = rateLimitService.consume(
                    verified.clientId(),
                    request.getMethod(),
                    request.getRequestURI()
            );
            if (rateLimit.enabled()) {
                response.setHeader("X-RateLimit-Limit", String.valueOf(rateLimit.limit()));
                response.setHeader(
                        "X-RateLimit-Remaining",
                        String.valueOf(rateLimit.remaining())
                );
            }
            if (!rateLimit.allowed()) {
                failureWriter.rateLimited(response, rateLimit.retryAfterSeconds());
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            verified.clientId(),
                            null,
                            List.of(new SimpleGrantedAuthority(
                                    verified.role() == ApiClientRole.ADMIN
                                            ? "ROLE_ADMIN"
                                            : "ROLE_API_CLIENT"
                            ))
                    );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);

            filterChain.doFilter(wrappedRequest, response);
        }
        catch (InvalidHmacRequestException exception) {
            failureWriter.unauthorized(response);
        }
        catch (DataAccessException exception) {
            failureWriter.serviceUnavailable(response);
        }
    }

    private byte[] readBody(HttpServletRequest request) throws IOException {
        int maximum = properties.maxRequestBodyBytes();
        if (request.getContentLengthLong() > maximum) {
            throw new RequestBodyTooLargeException();
        }
        byte[] body = request.getInputStream().readNBytes(maximum + 1);
        if (body.length > maximum) {
            throw new RequestBodyTooLargeException();
        }
        return body;
    }

    private static final class RequestBodyTooLargeException extends RuntimeException {
    }

    private static final class CachedBodyHttpServletRequest
            extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyHttpServletRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    if (readListener == null) {
                        throw new IllegalArgumentException("readListener must not be null");
                    }
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
