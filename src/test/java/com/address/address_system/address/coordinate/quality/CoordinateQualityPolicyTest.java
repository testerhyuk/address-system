package com.address.address_system.address.coordinate.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import com.address.address_system.address.coordinate.quality.CoordinateQualityPolicy.Decision;
import com.address.address_system.address.coordinate.quality.CoordinateQualityPolicy.Evaluation;
import com.address.address_system.address.coordinate.quality.CoordinateQualityPolicy.Evidence;
import com.address.address_system.address.coordinate.quality.CoordinateQualityPolicy.Reason;

import org.junit.jupiter.api.Test;

class CoordinateQualityPolicyTest {

    private final CoordinateQualityPolicy policy =
            new CoordinateQualityPolicy(properties());

    @Test
    void promotesAStableDominantCandidate() {
        Evaluation evaluation = policy.evaluate(new Evidence(
                9, 10, 1, new BigDecimal("5"), true, 0, null, true
        ));

        assertThat(evaluation.decision()).isEqualTo(Decision.PROMOTED);
        assertThat(evaluation.reason())
                .isEqualTo(Reason.AUTO_PROMOTION_CRITERIA_MET);
        assertThat(evaluation.qualityScore()).isEqualByComparingTo("0.8625");
    }

    @Test
    void confirmsAnExistingCoordinateWhenTheCandidateIsNearby() {
        Evaluation evaluation = policy.evaluate(new Evidence(
                9, 10, 1, new BigDecimal("5"), true, 0,
                new BigDecimal("6"), true
        ));

        assertThat(evaluation.decision()).isEqualTo(Decision.CONFIRMED);
        assertThat(evaluation.reason())
                .isEqualTo(Reason.ACTIVE_COORDINATE_CONFIRMED);
    }

    @Test
    void requestsReviewWhenTwoClustersCompete() {
        Evaluation evaluation = policy.evaluate(new Evidence(
                9, 18, 0, new BigDecimal("5"), true, 8, null, true
        ));

        assertThat(evaluation.decision()).isEqualTo(Decision.REVIEW_REQUIRED);
        assertThat(evaluation.reason())
                .isEqualTo(Reason.MULTIPLE_COMPETING_CLUSTERS);
    }

    @Test
    void rejectsACandidateWithLowOverallQuality() {
        Evaluation evaluation = policy.evaluate(new Evidence(
                5, 10, 5, new BigDecimal("20"), true, 0, null, true
        ));

        assertThat(evaluation.decision()).isEqualTo(Decision.REJECTED);
        assertThat(evaluation.reason()).isEqualTo(Reason.LOW_QUALITY_SCORE);
    }

    @Test
    void requestsReviewInsteadOfAutoPromotionForASuspendedTarget() {
        Evaluation evaluation = policy.evaluate(new Evidence(
                9, 10, 1, new BigDecimal("5"), true, 0, null, false
        ));

        assertThat(evaluation.decision()).isEqualTo(Decision.REVIEW_REQUIRED);
        assertThat(evaluation.reason()).isEqualTo(Reason.TARGET_SUSPENDED);
    }

    private CoordinateQualityProperties properties() {
        return new CoordinateQualityProperties(
                true,
                5,
                10,
                new BigDecimal("20"),
                new BigDecimal("0.30"),
                new BigDecimal("0.60"),
                new BigDecimal("0.15"),
                new BigDecimal("0.75"),
                new BigDecimal("0.55"),
                new BigDecimal("10"),
                new BigDecimal("100"),
                100,
                Duration.ofMinutes(10),
                "v1",
                3,
                Duration.ofMinutes(1),
                Duration.ofMinutes(30)
        );
    }
}
