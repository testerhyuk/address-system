package com.address.address_system.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ApiAuthenticationFailureWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApiAuthenticationFailureWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void unauthorized(HttpServletResponse response) throws IOException {
        write(
                response,
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "인증할 수 없는 요청입니다"
        );
    }

    public void payloadTooLarge(HttpServletResponse response) throws IOException {
        write(
                response,
                HttpStatus.CONTENT_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "요청 본문의 허용 크기를 초과했습니다"
        );
    }

    public void serviceUnavailable(HttpServletResponse response) throws IOException {
        write(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTHENTICATION_UNAVAILABLE",
                "현재 요청을 인증할 수 없습니다"
        );
    }

    public void rateLimited(
            HttpServletResponse response,
            long retryAfterSeconds
    ) throws IOException {
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        write(
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMIT_EXCEEDED",
                "API 호출 허용량을 초과했습니다"
        );
    }

    private void write(
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(
                response.getOutputStream(),
                new AuthenticationErrorResponse(code, message, clock.instant())
        );
    }

    private record AuthenticationErrorResponse(
            String code,
            String message,
            Instant timestamp
    ) {
    }
}
