package com.uniform.management.correctionrequest.dto;

import jakarta.validation.constraints.Size;

public record ResolveCorrectionRequest(
        @Size(max = 5000, message = "Phản hồi của quản trị viên không được vượt quá 5000 ký tự")
        String adminResponseNote,
        Integer newDeductedPoints,
        @Size(max = 5000, message = "Nội dung vi phạm không được vượt quá 5000 ký tự")
        String updatedViolationSummary
) {
}
