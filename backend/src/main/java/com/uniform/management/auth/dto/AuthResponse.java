package com.uniform.management.auth.dto;

import com.uniform.management.common.enums.Role;
import com.uniform.management.student.dto.StudentResponse;

public record AuthResponse(
        String tokenType,
        String accessToken,
        Long userId,
        String username,
        String email,
        Role role,
        StudentResponse student
) {
}
