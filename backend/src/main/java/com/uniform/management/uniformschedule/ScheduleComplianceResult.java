package com.uniform.management.uniformschedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.uniform.management.common.enums.ComplianceStatus;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;

public record ScheduleComplianceResult(
        boolean configured,
        boolean applicable,
        String reason,
        String className,
        DayOfWeek dayOfWeek,
        String dayLabel,
        String timeZone,
        Instant evaluatedAt,
        List<String> requiredComponents,
        List<String> detectedComponents,
        List<String> missingComponents,
        Integer missingRequiredComponentCount,
        Integer score,
        Integer deductedPoints,
        ComplianceStatus complianceStatus,
        JsonNode snapshot
) {
}
