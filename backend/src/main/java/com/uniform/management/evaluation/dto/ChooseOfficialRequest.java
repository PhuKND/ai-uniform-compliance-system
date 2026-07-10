package com.uniform.management.evaluation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ChooseOfficialRequest(
        @NotBlank String selectedMethod,
        String studentCode,
        @Min(value = 0, message = "Điểm trừ rèn luyện không được âm")
        @Max(value = 100, message = "Điểm trừ rèn luyện không được vượt quá 100")
        Integer deductedPoints,
        @Size(max = 1000) String adminNote
) {
}
