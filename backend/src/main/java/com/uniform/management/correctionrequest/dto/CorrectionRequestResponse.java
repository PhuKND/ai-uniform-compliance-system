package com.uniform.management.correctionrequest.dto;

import com.uniform.management.common.enums.CorrectionStatus;
import com.uniform.management.correctionrequest.CorrectionRequest;

import java.time.Instant;

public record CorrectionRequestResponse(
        Long id,
        Long evaluationHistoryId,
        String studentCode,
        String studentName,
        Integer deductionAtSubmission,
        Integer requestedDeduction,
        Integer deductionAfterDecision,
        String reason,
        String evidenceNote,
        Long evidenceImageId,
        String evidenceImageUrl,
        CorrectionStatus status,
        String adminResponseNote,
        String resolvedBy,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static CorrectionRequestResponse from(CorrectionRequest request) {
        Long evidenceImageId = request.getEvidenceImage() == null ? null : request.getEvidenceImage().getId();
        Integer deductionAtSubmission = request.getDeductionAtSubmission() == null
                ? request.getEvaluationHistory().getDeductedPoints()
                : request.getDeductionAtSubmission();
        return new CorrectionRequestResponse(
                request.getId(),
                request.getEvaluationHistory().getId(),
                request.getStudent().getStudentCode(),
                request.getStudent().getFullName(),
                deductionAtSubmission,
                request.getRequestedDeduction(),
                request.getDeductionAfterDecision(),
                request.getReason(),
                request.getEvidenceNote(),
                evidenceImageId,
                evidenceImageId == null ? null : "/api/images/" + evidenceImageId,
                request.getStatus(),
                request.getAdminResponseNote(),
                request.getResolvedBy() == null ? null : request.getResolvedBy().getEmail(),
                request.getResolvedAt(),
                request.getCreatedAt(),
                request.getUpdatedAt()
        );
    }
}
