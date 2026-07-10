package com.uniform.management.uniformschedule.dto;

import java.time.DayOfWeek;
import java.util.List;

public record StudentUniformScheduleResponse(
        String studentId,
        String studentCode,
        String studentName,
        String className,
        String timeZone,
        DayOfWeek today,
        List<StudentUniformScheduleDayResponse> days
) {
}
