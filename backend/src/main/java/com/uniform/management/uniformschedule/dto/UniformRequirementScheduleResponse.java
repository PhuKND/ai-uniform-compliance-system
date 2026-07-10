package com.uniform.management.uniformschedule.dto;

import java.time.Instant;
import java.util.List;

public record UniformRequirementScheduleResponse(
        String classId,
        String className,
        String timeZone,
        List<UniformComponentOption> componentOptions,
        List<UniformRequirementScheduleDayResponse> schedules,
        Instant updatedAt
) {
}
