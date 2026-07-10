package com.uniform.management.evaluation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;

import java.time.Instant;

public record MethodResultResponse(
        EvaluationMethod method,
        ComplianceStatus complianceStatus,
        Long processedImageId,
        String processedImageUrl,
        String aiProcessedImageUrl,
        JsonNode rawResult,
        String methodKey,
        String methodDisplayName,
        String processedImagePath,
        JsonNode result,
        String status,
        Integer score,
        String resultStatus,
        JsonNode validComponents,
        JsonNode missingComponents,
        JsonNode excludedComponents,
        String message,
        String note,
        String error,
        Instant completedAt,
        String evaluationMethod,
        String detectorModelId,
        String detectorModelVersion,
        JsonNode detectorConfidenceThreshold,
        Integer rawDetectionCount,
        Integer poseAcceptedDetectionCount,
        Integer finalUniqueDetectionCount,
        Integer duplicateRemovedCount,
        JsonNode scheduleResult,
        JsonNode detectorTrace
) {
}
