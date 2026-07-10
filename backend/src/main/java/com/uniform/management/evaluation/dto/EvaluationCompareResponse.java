package com.uniform.management.evaluation.dto;

import com.uniform.management.student.dto.StudentResponse;

import java.time.Instant;
import java.util.List;

public record EvaluationCompareResponse(
        Long runId,
        String requestedStudentCode,
        String recognizedStudentCode,
        Long originalImageId,
        MethodResultResponse method1,
        MethodResultResponse method2,
        Instant createdAt,
        String uniformAiEvaluationId,
        String preAiImagePath,
        String preAiImageUrl,
        String originalImageUrl,
        StudentResponse student,
        List<MethodResultResponse> candidates,
        Long jobId,
        String status,
        Instant updatedAt,
        List<MethodResultResponse> results
) {
}
