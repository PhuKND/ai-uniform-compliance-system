package com.uniform.management.uniformschedule;

import com.uniform.management.common.ApiResponse;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleResponse;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleUpdateRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/uniform-requirement-schedules")
public class UniformRequirementScheduleController {

    private final UniformRequirementScheduleService scheduleService;

    public UniformRequirementScheduleController(UniformRequirementScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{classId}")
    public ApiResponse<UniformRequirementScheduleResponse> getWeeklySchedule(@PathVariable String classId) {
        return ApiResponse.ok("Cấu hình lịch đồng phục", scheduleService.getWeeklySchedule(classId));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{classId}")
    public ApiResponse<UniformRequirementScheduleResponse> updateWeeklySchedule(
            @PathVariable String classId,
            @RequestBody UniformRequirementScheduleUpdateRequest request
    ) {
        return ApiResponse.ok("Đã lưu cấu hình lịch đồng phục", scheduleService.updateWeeklySchedule(classId, request));
    }
}
