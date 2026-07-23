package com.address.address_system.address.coordinate.analysis;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("address.coordinate-analysis")
public record CoordinateAnalysisProperties(
        boolean enabled,
        BigDecimal epsMeters,
        int minPoints,
        int minSamples,
        Duration analysisWindow,
        int batchSize,
        Duration scheduleInterval,
        int maxAttempts,
        Duration initialRetryDelay,
        Duration maxRetryDelay
) {
    public CoordinateAnalysisProperties {
        if (epsMeters == null || epsMeters.signum() <= 0 || minPoints < 2
                || minSamples < minPoints || analysisWindow == null
                || analysisWindow.isNegative() || analysisWindow.isZero()
                || batchSize < 1 || scheduleInterval == null
                || scheduleInterval.isNegative() || scheduleInterval.isZero()
                || maxAttempts < 1 || initialRetryDelay == null
                || initialRetryDelay.isNegative() || initialRetryDelay.isZero()
                || maxRetryDelay == null || maxRetryDelay.isNegative()
                || maxRetryDelay.isZero()
                || maxRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException("invalid coordinate analysis properties");
        }
    }
}
