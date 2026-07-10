package com.uniform.management.uniformschedule;

import com.uniform.management.common.ApiResponse;
import com.uniform.management.security.SecurityUtils;
import com.uniform.management.uniformschedule.dto.StudentUniformScheduleResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/student/uniform-schedule")
public class StudentUniformScheduleController {

    private final UniformRequirementScheduleService scheduleService;

    public StudentUniformScheduleController(UniformRequirementScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping
    public ApiResponse<StudentUniformScheduleResponse> myWeeklySchedule() {
        return ApiResponse.ok("Lịch đồng phục của học sinh", scheduleService.getStudentWeeklySchedule(SecurityUtils.currentStudent()));
    }
}
