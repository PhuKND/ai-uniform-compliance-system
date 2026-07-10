package com.uniform.management.student.dto;

import com.uniform.management.common.enums.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record StudentCreateRequest(
        @NotBlank @Size(max = 160) String fullName,
        Gender gender,
        LocalDate dateOfBirth,
        @Size(max = 64) String className,
        @Size(max = 32) String schoolYear,
        @Size(max = 32) String phone,
        @Email @Size(max = 160) String email,
        @Size(max = 500) String address
) {
}
