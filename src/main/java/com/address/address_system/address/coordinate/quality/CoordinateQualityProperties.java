package com.address.address_system.address.coordinate.quality;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("address.coordinate-quality")
public record CoordinateQualityProperties(
        boolean enabled,
        int minCandidateSamples,
        int fullScoreSampleCount,
        BigDecimal maxRadiusMeters,
        BigDecimal maxOutlierRatio,
        BigDecimal minDominantRatio,
        BigDecimal minDominanceGap,
        BigDecimal minPromotionScore,
        BigDecimal minReviewScore,
        BigDecimal confirmationDistanceMeters,
        BigDecimal maxAutomaticShiftMeters,
        int batchSize,
        Duration scheduleInterval,
        String policyVersion,
        int maxAttempts,
        Duration initialRetryDelay,
        Duration maxRetryDelay
) {
    public CoordinateQualityProperties {
        if (minCandidateSamples < 1 || fullScoreSampleCount < minCandidateSamples
                || !positive(maxRadiusMeters) || !ratio(maxOutlierRatio)
                || !ratio(minDominantRatio) || !ratio(minDominanceGap)
                || !ratio(minPromotionScore) || !ratio(minReviewScore)
                || minReviewScore.compareTo(minPromotionScore) > 0
                || !positive(confirmationDistanceMeters)
                || !positive(maxAutomaticShiftMeters)
                || confirmationDistanceMeters.compareTo(maxAutomaticShiftMeters) > 0
                || batchSize < 1 || scheduleInterval == null
                || scheduleInterval.isZero() || scheduleInterval.isNegative()
                || policyVersion == null || policyVersion.isBlank()
                || policyVersion.length() > 32 || maxAttempts < 1
                || initialRetryDelay == null || initialRetryDelay.isNegative()
                || initialRetryDelay.isZero() || maxRetryDelay == null
                || maxRetryDelay.isNegative() || maxRetryDelay.isZero()
                || maxRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException("invalid coordinate quality properties");
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean ratio(BigDecimal value) {
        return value != null && value.signum() >= 0
                && value.compareTo(BigDecimal.ONE) <= 0;
    }
}
