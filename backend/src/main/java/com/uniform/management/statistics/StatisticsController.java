package com.uniform.management.statistics;

import com.uniform.management.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin")
    public ApiResponse<Map<String, Object>> admin() {
        return ApiResponse.ok("Thống kê quản trị", statisticsService.adminStatistics());
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/student/me")
    public ApiResponse<Map<String, Object>> student() {
        return ApiResponse.ok("Thống kê học sinh", statisticsService.studentStatistics());
    }
}
