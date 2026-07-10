package com.uniform.management.uniformschedule.dto;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;

public record UniformRequirementScheduleDayResponse(
        DayOfWeek dayOfWeek,
        String dayLabel,
        boolean configured,
        List<String> requiredComponents,
        List<UniformComponentOption> requiredComponentDetails,
        Instant updatedAt
) {
}
