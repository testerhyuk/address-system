package com.address.address_system.address.coordinate.quality;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

@Component
public class CoordinateQualityPolicy {

    private static final BigDecimal DOMINANCE_WEIGHT = new BigDecimal("0.35");
    private static final BigDecimal CLEANLINESS_WEIGHT = new BigDecimal("0.25");
    private static final BigDecimal COHESION_WEIGHT = new BigDecimal("0.25");
    private static final BigDecimal MATURITY_WEIGHT = new BigDecimal("0.15");

    private final CoordinateQualityProperties properties;

    public CoordinateQualityPolicy(CoordinateQualityProperties properties) {
        this.properties = properties;
    }

    public Evaluation evaluate(Evidence evidence) {
        BigDecimal total = BigDecimal.valueOf(evidence.totalSampleCount());
        BigDecimal dominance = divide(evidence.candidateSampleCount(), total);
        BigDecimal outlierRatio = divide(evidence.outlierCount(), total);
        BigDecimal cleanliness = BigDecimal.ONE.subtract(outlierRatio);
        BigDecimal cohesion = BigDecimal.ONE.subtract(
                evidence.radiusMeters().divide(
                        properties.maxRadiusMeters(), 8, RoundingMode.HALF_UP
                ).min(BigDecimal.ONE)
        );
        BigDecimal maturity = BigDecimal.valueOf(evidence.candidateSampleCount())
                .divide(BigDecimal.valueOf(properties.fullScoreSampleCount()), 8,
                        RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
        BigDecimal dominanceGap = evidence.topCandidate()
                ? BigDecimal.valueOf(
                        evidence.candidateSampleCount() - evidence.secondSampleCount()
                ).max(BigDecimal.ZERO).divide(total, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal score = dominance.multiply(DOMINANCE_WEIGHT)
                .add(cleanliness.multiply(CLEANLINESS_WEIGHT))
                .add(cohesion.multiply(COHESION_WEIGHT))
                .add(maturity.multiply(MATURITY_WEIGHT));

        Decision decision;
        Reason reason;
        if (evidence.candidateSampleCount() < properties.minCandidateSamples()) {
            decision = Decision.REJECTED;
            reason = Reason.INSUFFICIENT_SAMPLES;
        }
        else if (score.compareTo(properties.minReviewScore()) < 0) {
            decision = Decision.REJECTED;
            reason = Reason.LOW_QUALITY_SCORE;
        }
        else if (!evidence.topCandidate()
                || dominanceGap.compareTo(properties.minDominanceGap()) < 0) {
            decision = Decision.REVIEW_REQUIRED;
            reason = Reason.MULTIPLE_COMPETING_CLUSTERS;
        }
        else if (evidence.radiusMeters().compareTo(properties.maxRadiusMeters()) > 0) {
            decision = Decision.REVIEW_REQUIRED;
            reason = Reason.WIDE_CLUSTER;
        }
        else if (outlierRatio.compareTo(properties.maxOutlierRatio()) > 0) {
            decision = Decision.REVIEW_REQUIRED;
            reason = Reason.HIGH_OUTLIER_RATIO;
        }
        else if (dominance.compareTo(properties.minDominantRatio()) < 0) {
            decision = Decision.REVIEW_REQUIRED;
            reason = Reason.LOW_DOMINANCE;
        }
        else if (score.compareTo(properties.minPromotionScore()) < 0) {
            decision = Decision.REVIEW_REQUIRED;
            reason = Reason.PROMOTION_SCORE_NOT_MET;
        }
        else if (evidence.distanceFromActiveMeters() != null
                && evidence.distanceFromActiveMeters().compareTo(
                        properties.maxAutomaticShiftMeters()) > 0) {
            decision = Decision.REVIEW_REQUIRED;
            reason = Reason.EXCESSIVE_SHIFT;
        }
        else if (!evidence.automaticPromotionAllowed()) {
            decision = Decision.REVIEW_REQUIRED;
            reason = Reason.TARGET_SUSPENDED;
        }
        else if (evidence.distanceFromActiveMeters() != null
                && evidence.distanceFromActiveMeters().compareTo(
                        properties.confirmationDistanceMeters()) <= 0) {
            decision = Decision.CONFIRMED;
            reason = Reason.ACTIVE_COORDINATE_CONFIRMED;
        }
        else {
            decision = Decision.PROMOTED;
            reason = Reason.AUTO_PROMOTION_CRITERIA_MET;
        }

        return new Evaluation(
                decision,
                reason,
                scale(score),
                scale(dominance),
                scale(outlierRatio),
                scale(dominanceGap)
        );
    }

    private BigDecimal divide(int numerator, BigDecimal denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(denominator, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE)
                .setScale(4, RoundingMode.HALF_UP);
    }

    public record Evidence(
            int candidateSampleCount,
            int totalSampleCount,
            int outlierCount,
            BigDecimal radiusMeters,
            boolean topCandidate,
            int secondSampleCount,
            BigDecimal distanceFromActiveMeters,
            boolean automaticPromotionAllowed
    ) {
        public Evidence {
            if (candidateSampleCount < 1 || totalSampleCount < candidateSampleCount
                    || outlierCount < 0 || outlierCount > totalSampleCount
                    || radiusMeters == null || radiusMeters.signum() < 0
                    || secondSampleCount < 0
                    || (distanceFromActiveMeters != null
                    && distanceFromActiveMeters.signum() < 0)) {
                throw new IllegalArgumentException("invalid coordinate quality evidence");
            }
        }
    }

    public record Evaluation(
            Decision decision,
            Reason reason,
            BigDecimal qualityScore,
            BigDecimal dominanceRatio,
            BigDecimal outlierRatio,
            BigDecimal dominanceGap
    ) {
    }

    public enum Decision {
        PROMOTED,
        CONFIRMED,
        REVIEW_REQUIRED,
        REJECTED
    }

    public enum Reason {
        AUTO_PROMOTION_CRITERIA_MET,
        ACTIVE_COORDINATE_CONFIRMED,
        INSUFFICIENT_SAMPLES,
        LOW_QUALITY_SCORE,
        MULTIPLE_COMPETING_CLUSTERS,
        WIDE_CLUSTER,
        HIGH_OUTLIER_RATIO,
        LOW_DOMINANCE,
        PROMOTION_SCORE_NOT_MET,
        EXCESSIVE_SHIFT,
        TARGET_SUSPENDED
    }
}
