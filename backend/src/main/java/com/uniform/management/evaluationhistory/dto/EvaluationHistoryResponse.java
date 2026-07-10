package com.uniform.management.evaluationhistory.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.evaluationhistory.EvaluationHistory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

public record EvaluationHistoryResponse(
        Long id,
        String studentCode,
        String studentName,
        String className,
        LocalDate dateOfBirth,
        Integer studentAgeAtEvaluation,
        String recognizedStudentCode,
        String uniformAiEvaluationId,
        EvaluationMethod selectedMethod,
        ComplianceStatus complianceStatus,
        boolean hasWhiteShirt,
        boolean hasYouthUnionShirt,
        boolean hasBlackTrousers,
        boolean hasRedScarf,
        Boolean shirtTuckedIn,
        Boolean clothesWrinkled,
        Boolean clothesDirty,
        Boolean clothesTorn,
        boolean overallCompliant,
        Set<String> violationTypes,
        String violationSummary,
        String aiComment,
        int deductedPoints,
        Long originalImageId,
        Long processedImageId,
        String originalImageUrl,
        String processedImageUrl,
        String preAiImagePath,
        String preAiImageUrl,
        String selectedProcessedImagePath,
        String selectedProcessedImageUrl,
        Integer finalScore,
        String finalComment,
        boolean scheduleConfigured,
        boolean scheduleApplicable,
        String scheduleReason,
        String scheduleClassName,
        String scheduleDayOfWeek,
        String scheduleDayLabel,
        String scheduleTimeZone,
        Instant scheduleEvaluatedAt,
        Integer scheduleScore,
        Integer scheduleDeductedPoints,
        JsonNode scheduleRequiredComponents,
        JsonNode scheduleDetectedComponents,
        JsonNode scheduleMissingComponents,
        JsonNode scheduleSnapshot,
        JsonNode acceptedComponents,
        JsonNode missingComponents,
        JsonNode rejectedComponents,
        JsonNode tuckInAssessment,
        JsonNode appearanceAssessment,
        String createdBy,
        String adminNote,
        Instant createdAt,
        Instant updatedAt
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static EvaluationHistoryResponse from(EvaluationHistory history) {
        Long originalImageId = history.getOriginalImage() == null ? null : history.getOriginalImage().getId();
        Long processedImageId = history.getProcessedImage() == null ? null : history.getProcessedImage().getId();
        String originalImageUrl = originalImageId == null ? null : "/api/images/" + originalImageId;
        String processedImageUrl = processedImageId == null ? null : "/api/images/" + processedImageId;
        return new EvaluationHistoryResponse(
                history.getId(),
                history.getStudentCodeSnapshot(),
                history.getStudentNameSnapshot(),
                history.getClassNameSnapshot(),
                history.getDateOfBirthSnapshot(),
                history.getStudentAgeAtEvaluation(),
                history.getRecognizedStudentCode(),
                history.getUniformAiEvaluationId(),
                history.getSelectedMethod(),
                history.getComplianceStatus(),
                history.isHasWhiteShirt(),
                history.isHasYouthUnionShirt(),
                history.isHasBlackTrousers(),
                history.isHasRedScarf(),
                history.getShirtTuckedIn(),
                history.getClothesWrinkled(),
                history.getClothesDirty(),
                history.getClothesTorn(),
                history.isOverallCompliant(),
                history.getViolationTypes() == null ? Set.of() : new LinkedHashSet<>(history.getViolationTypes()),
                history.getViolationSummary(),
                history.getAiComment(),
                history.getDeductedPoints(),
                originalImageId,
                processedImageId,
                originalImageUrl,
                processedImageUrl,
                null,
                originalImageUrl,
                null,
                processedImageUrl,
                history.getFinalScore(),
                history.getFinalComment(),
                history.isScheduleConfigured(),
                history.isScheduleApplicable(),
                history.getScheduleReason(),
                history.getScheduleClassName(),
                history.getScheduleDayOfWeek(),
                history.getScheduleDayLabel(),
                history.getScheduleTimeZone(),
                history.getScheduleEvaluatedAt(),
                history.getScheduleScore(),
                history.getScheduleDeductedPoints(),
                readJson(history.getScheduleRequiredComponentsJson()),
                readJson(history.getScheduleDetectedComponentsJson()),
                readJson(history.getScheduleMissingComponentsJson()),
                readJson(history.getScheduleSnapshotJson()),
                readJson(history.getAcceptedComponentsJson()),
                readJson(history.getMissingComponentsJson()),
                readJson(history.getRejectedComponentsJson()),
                readJson(history.getTuckInAssessmentJson()),
                readJson(history.getAppearanceAssessmentJson()),
                history.getCreatedBy().getEmail(),
                history.getAdminNote(),
                history.getCreatedAt(),
                history.getUpdatedAt()
        );
    }

    private static JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception ignored) {
            return NullNode.getInstance();
        }
    }
}
