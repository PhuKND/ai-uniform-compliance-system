package com.uniform.management.uniformschedule.dto;

import java.time.DayOfWeek;
import java.util.List;

public record UniformRequirementScheduleDayRequest(
        DayOfWeek dayOfWeek,
        List<String> requiredComponents
) {
}
