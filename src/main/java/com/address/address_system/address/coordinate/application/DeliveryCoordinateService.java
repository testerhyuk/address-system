package com.address.address_system.address.coordinate.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.address.address_system.address.coordinate.config.DeliveryCoordinateProperties;
import com.address.address_system.address.coordinate.model.DeliveryCoordinateResult;
import com.address.address_system.address.coordinate.persistence.DeliveryCoordinateRepository;
import com.address.address_system.address.coordinate.persistence.DeliveryCoordinateRepository.StoredEvent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryCoordinateService {

    private final DeliveryCoordinateRepository repository;
    private final DeliveryCoordinateProperties properties;
    private final Clock clock;

    public DeliveryCoordinateService(
            DeliveryCoordinateRepository repository,
            DeliveryCoordinateProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public DeliveryCoordinateResult receive(Command command) {
        validate(command);
        String fingerprint = fingerprint(command);

        StoredEvent existing = repository.findByEventId(command.eventId()).orElse(null);
        if (existing != null) {
            return duplicate(existing, fingerprint);
        }
        if (!repository.activeTargetExists(command.deliveryTargetId())) {
            throw new DeliveryCoordinateException(
                    FailureCode.DELIVERY_TARGET_NOT_FOUND,
                    "활성 배송 대상을 찾을 수 없습니다"
            );
        }

        Instant receivedAt = clock.instant();
        UUID sampleId = UUID.randomUUID();
        boolean inserted = repository.insert(
                sampleId,
                command.eventId(),
                command.deliveryTargetId(),
                command.latitude(),
                command.longitude(),
                command.gpsAccuracyMeters(),
                command.completedAt(),
                receivedAt,
                receivedAt.plus(properties.rawRetention()),
                fingerprint
        );
        if (inserted) {
            return new DeliveryCoordinateResult(
                    sampleId,
                    command.eventId(),
                    "PENDING",
                    false
            );
        }
        return duplicate(
                repository.findByEventId(command.eventId()).orElseThrow(),
                fingerprint
        );
    }

    private void validate(Command command) {
        if (command.eventId().version() != 4 || command.eventId().variant() != 2) {
            throw invalid("eventId는 무작위 UUID v4여야 합니다");
        }
        if (command.gpsAccuracyMeters().signum() <= 0
                || command.gpsAccuracyMeters().compareTo(
                        properties.maxGpsAccuracyMeters()
                ) > 0) {
            throw invalid("GPS 정확도가 허용 범위를 벗어났습니다");
        }
        Instant now = clock.instant();
        if (command.completedAt().isBefore(now.minus(properties.maxEventAge()))
                || command.completedAt().isAfter(
                        now.plus(properties.allowedFutureSkew())
                )) {
            throw invalid("배달 완료 시각이 허용 범위를 벗어났습니다");
        }
    }

    private DeliveryCoordinateResult duplicate(
            StoredEvent existing,
            String fingerprint
    ) {
        if (!MessageDigest.isEqual(
                existing.fingerprint().getBytes(StandardCharsets.US_ASCII),
                fingerprint.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new DeliveryCoordinateException(
                    FailureCode.EVENT_ID_CONFLICT,
                    "동일한 eventId에 다른 요청 값이 전달되었습니다"
            );
        }
        return new DeliveryCoordinateResult(
                existing.sampleId(),
                existing.eventId(),
                existing.processingStatus(),
                true
        );
    }

    String fingerprint(Command command) {
        String canonical = String.join(
                "\n",
                command.deliveryTargetId().toString(),
                normalize(command.latitude()),
                normalize(command.longitude()),
                normalize(command.gpsAccuracyMeters()),
                command.completedAt().toString()
        );
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            canonical.getBytes(StandardCharsets.UTF_8)
                    )
            );
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private DeliveryCoordinateException invalid(String message) {
        return new DeliveryCoordinateException(FailureCode.INVALID_COORDINATE_EVENT, message);
    }

    public record Command(
            UUID eventId,
            UUID deliveryTargetId,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal gpsAccuracyMeters,
            Instant completedAt
    ) {
    }

    public enum FailureCode {
        INVALID_COORDINATE_EVENT,
        DELIVERY_TARGET_NOT_FOUND,
        EVENT_ID_CONFLICT
    }

    public static class DeliveryCoordinateException extends RuntimeException {
        private final FailureCode failureCode;

        public DeliveryCoordinateException(FailureCode failureCode, String message) {
            super(message);
            this.failureCode = failureCode;
        }

        public FailureCode getFailureCode() {
            return failureCode;
        }
    }
}
