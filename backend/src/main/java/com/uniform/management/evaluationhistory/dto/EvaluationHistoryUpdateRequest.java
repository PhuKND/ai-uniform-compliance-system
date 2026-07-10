package com.uniform.management.evaluationhistory.dto;

import com.uniform.management.common.enums.ComplianceStatus;
import jakarta.validation.constraints.Size;

public record EvaluationHistoryUpdateRequest(
        ComplianceStatus complianceStatus,
        Integer deductedPoints,
        @Size(max = 1000) String adminNote,
        @Size(max = 5000) String violationSummary
) {
}
