package com.uniform.management.uniformschedule.dto;

public record UniformClassResponse(
        String classId,
        String className,
        long studentCount
) {
}
