package com.uniform.management.student.dto;

import com.uniform.management.common.enums.Role;
import com.uniform.management.user.UserAccount;

public record StudentAccountResponse(
        Long userId,
        String username,
        String email,
        Role role,
        boolean enabled,
        StudentResponse student
) {
    public static StudentAccountResponse from(UserAccount user) {
        return new StudentAccountResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                StudentResponse.from(user.getStudent(), user)
        );
    }
}
