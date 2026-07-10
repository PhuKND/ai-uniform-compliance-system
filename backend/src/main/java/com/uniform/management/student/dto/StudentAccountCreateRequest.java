package com.uniform.management.student.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StudentAccountCreateRequest(
        @NotBlank @Size(max = 100) String username,
        @Email @NotBlank @Size(max = 160) String email,
        @NotBlank @Size(min = 6, max = 100) String password,
        @Size(max = 100) String confirmPassword
) {
}
