package com.uniform.management.uniformschedule.dto;

import java.util.List;

public record UniformRequirementScheduleUpdateRequest(
        List<UniformRequirementScheduleDayRequest> schedules
) {
}
