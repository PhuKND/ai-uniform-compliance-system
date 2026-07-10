package com.uniform.management.evaluation;

import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.uniformschedule.ScheduleComplianceResult;
import com.uniform.management.uniformschedule.UniformComponent;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class UniformComplianceScoreService {

    public int scheduleComplianceScore(int missingRequiredComponentCount) {
        return Math.max(0, 100 - Math.max(0, missingRequiredComponentCount) * 20);
    }

    public ComplianceStatus statusForScore(int score, boolean hasReviewIssue) {
        if (score >= 80) {
            return hasReviewIssue ? ComplianceStatus.NEEDS_REVIEW : ComplianceStatus.COMPLIANT;
        }
        if (score >= 65) {
            return ComplianceStatus.NEEDS_REVIEW;
        }
        return ComplianceStatus.NON_COMPLIANT;
    }

    public int automaticConductDeduction(Integer canonicalScore) {
        if (canonicalScore == null || canonicalScore >= 80) {
            return 0;
        }
        if (canonicalScore >= 65) {
            return 10;
        }
        if (canonicalScore >= 50) {
            return 20;
        }
        return 30;
    }

    public ScoreDecision decide(
            ScheduleComplianceResult scheduleResult,
            UniformComplianceService.UniformComplianceDecision aiDecision,
            ComplianceStatus aiStatus
    ) {
        LinkedHashSet<String> reviewReasons = reviewReasons(aiDecision, aiStatus);
        if (scheduleResult == null || !scheduleResult.applicable() || scheduleResult.score() == null) {
            if (scheduleResult != null && scheduleResult.reason() != null) {
                reviewReasons.add("SCHEDULE_" + scheduleResult.reason().toUpperCase(Locale.ROOT));
            }
            return new ScoreDecision(
                    null,
                    ComplianceStatus.NEEDS_REVIEW,
                    0,
                    true,
                    reviewReasons,
                    finalComment(scheduleResult, aiDecision, null, reviewReasons)
            );
        }

        int canonicalScore = scheduleResult.score();
        boolean hasReviewIssue = !reviewReasons.isEmpty();
        ComplianceStatus status = statusForScore(canonicalScore, hasReviewIssue);
        return new ScoreDecision(
                canonicalScore,
                status,
                automaticConductDeduction(canonicalScore),
                hasReviewIssue,
                reviewReasons,
                finalComment(scheduleResult, aiDecision, canonicalScore, reviewReasons)
        );
    }

    private LinkedHashSet<String> reviewReasons(
            UniformComplianceService.UniformComplianceDecision aiDecision,
            ComplianceStatus aiStatus
    ) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (aiStatus == null || aiStatus == ComplianceStatus.NEEDS_REVIEW) {
            reasons.add("AI_NEEDS_REVIEW");
        } else if (aiStatus == ComplianceStatus.PARTIALLY_COMPLIANT) {
            reasons.add("AI_PARTIAL_REVIEW");
        }
        if (aiDecision == null) {
            return reasons;
        }
        if (aiDecision.shirtTuckedIn() == null) {
            reasons.add("TUCK_IN_UNCERTAIN");
        } else if (Boolean.FALSE.equals(aiDecision.shirtTuckedIn())) {
            reasons.add("SHIRT_NOT_TUCKED");
        }
        if (Boolean.TRUE.equals(aiDecision.clothesWrinkled())) {
            reasons.add("WRINKLED_CLOTHES");
        }
        if (Boolean.TRUE.equals(aiDecision.clothesDirty())) {
            reasons.add("DIRTY_CLOTHES");
        }
        if (Boolean.TRUE.equals(aiDecision.clothesTorn())) {
            reasons.add("TORN_CLOTHES");
        }
        return reasons;
    }

    private String finalComment(
            ScheduleComplianceResult scheduleResult,
            UniformComplianceService.UniformComplianceDecision aiDecision,
            Integer canonicalScore,
            Set<String> reviewReasons
    ) {
        StringBuilder comment = new StringBuilder();
        if (scheduleResult == null || !scheduleResult.applicable()) {
            comment.append("Chưa đủ dữ liệu lịch đồng phục lớp để tính điểm chính thức; cần kiểm tra lại.");
        } else if (scheduleResult.missingComponents().isEmpty()) {
            comment.append("Học sinh đủ thành phần bắt buộc theo lịch đồng phục lớp.");
        } else {
            comment.append("Thiếu theo lịch đồng phục lớp: ")
                    .append(String.join(", ", componentLabels(scheduleResult.missingComponents())))
                    .append(".");
        }
        if (canonicalScore != null) {
            comment.append(" Điểm tuân thủ lịch lớp: ").append(canonicalScore).append(".");
        }
        if (!reviewReasons.isEmpty()) {
            comment.append(" Cần kiểm tra thêm: ")
                    .append(String.join(", ", reviewReasons.stream().map(this::reviewReasonLabel).toList()))
                    .append(".");
        }
        String aiComment = aiDecision == null ? null : aiDecision.finalComment();
        if (aiComment != null && !aiComment.isBlank()) {
            comment.append(" Ghi chú AI: ").append(aiComment);
        }
        return comment.toString();
    }

    private List<String> componentLabels(List<String> componentKeys) {
        return componentKeys.stream()
                .map(key -> UniformComponent.fromKey(key).map(UniformComponent::label).orElse(key))
                .toList();
    }

    private String reviewReasonLabel(String reason) {
        return switch (reason) {
            case "AI_NEEDS_REVIEW", "AI_PARTIAL_REVIEW" -> "AI cần kiểm tra lại";
            case "TUCK_IN_UNCERTAIN" -> "tình trạng sơ vin chưa rõ";
            case "SHIRT_NOT_TUCKED" -> "áo chưa sơ vin";
            case "WRINKLED_CLOTHES" -> "đồng phục bị nhăn";
            case "DIRTY_CLOTHES" -> "đồng phục bị bẩn";
            case "TORN_CLOTHES" -> "đồng phục bị rách";
            default -> reason;
        };
    }

    public record ScoreDecision(
            Integer canonicalScore,
            ComplianceStatus complianceStatus,
            int automaticConductDeduction,
            boolean reviewIssue,
            Set<String> reviewReasons,
            String finalComment
    ) {
    }
}
