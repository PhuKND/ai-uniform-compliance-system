package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.node.NullNode;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.uniformschedule.ScheduleComplianceResult;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UniformComplianceScoreServiceTest {

    private final UniformComplianceScoreService scoreService = new UniformComplianceScoreService();

    @Test
    void calculatesScheduleComplianceScoreFromMissingRequiredComponents() {
        assertThat(scoreService.scheduleComplianceScore(0)).isEqualTo(100);
        assertThat(scoreService.scheduleComplianceScore(1)).isEqualTo(80);
        assertThat(scoreService.scheduleComplianceScore(2)).isEqualTo(60);
        assertThat(scoreService.scheduleComplianceScore(3)).isEqualTo(40);
        assertThat(scoreService.scheduleComplianceScore(4)).isEqualTo(20);
        assertThat(scoreService.scheduleComplianceScore(5)).isZero();
        assertThat(scoreService.scheduleComplianceScore(6)).isZero();
        assertThat(scoreService.scheduleComplianceScore(-1)).isEqualTo(100);
    }

    @Test
    void calculatesAutomaticConductDeductionFromCanonicalScore() {
        assertThat(scoreService.automaticConductDeduction(100)).isZero();
        assertThat(scoreService.automaticConductDeduction(80)).isZero();
        assertThat(scoreService.automaticConductDeduction(79)).isEqualTo(10);
        assertThat(scoreService.automaticConductDeduction(65)).isEqualTo(10);
        assertThat(scoreService.automaticConductDeduction(64)).isEqualTo(20);
        assertThat(scoreService.automaticConductDeduction(50)).isEqualTo(20);
        assertThat(scoreService.automaticConductDeduction(49)).isEqualTo(30);
    }

    @Test
    void mapsScoresAndReviewIssuesToCanonicalStatus() {
        assertThat(scoreService.statusForScore(100, false)).isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(scoreService.statusForScore(100, true)).isEqualTo(ComplianceStatus.NEEDS_REVIEW);
        assertThat(scoreService.statusForScore(80, false)).isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(scoreService.statusForScore(80, true)).isEqualTo(ComplianceStatus.NEEDS_REVIEW);
        assertThat(scoreService.statusForScore(65, false)).isEqualTo(ComplianceStatus.NEEDS_REVIEW);
        assertThat(scoreService.statusForScore(64, false)).isEqualTo(ComplianceStatus.NON_COMPLIANT);
    }

    @Test
    void decisionPolicyKeepsAppearanceAndTuckInIssuesAsNeedsReview() {
        assertThat(scoreService.decide(scheduleResult(100), aiDecision(Boolean.TRUE, false, false, false), ComplianceStatus.COMPLIANT)
                .complianceStatus()).isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(scoreService.decide(scheduleResult(100), aiDecision(Boolean.TRUE, false, true, false), ComplianceStatus.COMPLIANT)
                .complianceStatus()).isEqualTo(ComplianceStatus.NEEDS_REVIEW);
        assertThat(scoreService.decide(scheduleResult(100), aiDecision(Boolean.TRUE, true, false, false), ComplianceStatus.COMPLIANT)
                .complianceStatus()).isEqualTo(ComplianceStatus.NEEDS_REVIEW);
        assertThat(scoreService.decide(scheduleResult(100), aiDecision(Boolean.TRUE, false, false, true), ComplianceStatus.COMPLIANT)
                .complianceStatus()).isEqualTo(ComplianceStatus.NEEDS_REVIEW);
        assertThat(scoreService.decide(scheduleResult(100), aiDecision(null, false, false, false), ComplianceStatus.COMPLIANT)
                .reviewReasons()).contains("TUCK_IN_UNCERTAIN");
        assertThat(scoreService.decide(scheduleResult(80), aiDecision(Boolean.TRUE, false, false, false), ComplianceStatus.COMPLIANT)
                .complianceStatus()).isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(scoreService.decide(scheduleResult(80), aiDecision(Boolean.TRUE, false, true, false), ComplianceStatus.COMPLIANT)
                .complianceStatus()).isEqualTo(ComplianceStatus.NEEDS_REVIEW);
        assertThat(scoreService.decide(scheduleResult(65), aiDecision(Boolean.TRUE, false, false, false), ComplianceStatus.COMPLIANT)
                .complianceStatus()).isEqualTo(ComplianceStatus.NEEDS_REVIEW);
        assertThat(scoreService.decide(scheduleResult(64), aiDecision(Boolean.TRUE, false, false, false), ComplianceStatus.COMPLIANT)
                .complianceStatus()).isEqualTo(ComplianceStatus.NON_COMPLIANT);
    }

    private ScheduleComplianceResult scheduleResult(int score) {
        return new ScheduleComplianceResult(
                true,
                true,
                null,
                "6A3",
                DayOfWeek.MONDAY,
                "Thứ Hai",
                "Asia/Ho_Chi_Minh",
                Instant.parse("2026-06-29T02:00:00Z"),
                List.of(),
                List.of(),
                List.of(),
                0,
                score,
                scoreService.automaticConductDeduction(score),
                scoreService.statusForScore(score, false),
                NullNode.getInstance()
        );
    }

    private UniformComplianceService.UniformComplianceDecision aiDecision(
            Boolean shirtTuckedIn,
            boolean clothesWrinkled,
            boolean clothesDirty,
            boolean clothesTorn
    ) {
        return new UniformComplianceService.UniformComplianceDecision(
                ComplianceStatus.COMPLIANT,
                true,
                Set.of(),
                NullNode.getInstance(),
                NullNode.getInstance(),
                NullNode.getInstance(),
                NullNode.getInstance(),
                NullNode.getInstance(),
                Set.of(),
                "",
                "",
                100,
                shirtTuckedIn,
                clothesWrinkled,
                clothesDirty,
                clothesTorn,
                false,
                "TEST"
        );
    }
}
