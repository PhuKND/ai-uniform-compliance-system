package com.uniform.management.student.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record StudentSelfUpdateRequest(
        @Size(max = 32) String phone,
        @Email @Size(max = 160) String email,
        @Size(max = 500) String address
) {
}
