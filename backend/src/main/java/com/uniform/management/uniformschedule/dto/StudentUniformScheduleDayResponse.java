package com.uniform.management.uniformschedule.dto;

import java.time.DayOfWeek;
import java.util.List;

public record StudentUniformScheduleDayResponse(
        DayOfWeek dayOfWeek,
        String displayName,
        boolean isToday,
        boolean hasSchedule,
        List<StudentUniformScheduleComponentResponse> requiredComponents
) {
}
