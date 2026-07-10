package com.uniform.management.evaluation;

import com.uniform.management.common.ApiResponse;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.evaluation.dto.EvaluationCompareResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/evaluations")
@PreAuthorize("hasRole('ADMIN')")
public class AdvancedEvaluationController {

    private final EvaluationService evaluationService;

    public AdvancedEvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/yolov8-v2")
    public ApiResponse<EvaluationCompareResponse> yolov8V2(
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String studentCode
    ) {
        return ApiResponse.ok(
                "Đã chạy đánh giá YOLOv8 V2",
                evaluationService.advanced(image, studentCode, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE)
        );
    }

    @PostMapping("/grounding-dino-v2")
    public ApiResponse<EvaluationCompareResponse> groundingDinoV2(
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String studentCode
    ) {
        return ApiResponse.ok(
                "Đã chạy đánh giá Grounding DINO V2",
                evaluationService.advanced(image, studentCode, EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE)
        );
    }

    @PostMapping("/lightweight")
    public ApiResponse<EvaluationCompareResponse> lightweight(
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String studentCode,
            @RequestParam(required = false) String selectedMethod,
            @RequestParam(required = false) String uniformMethod
    ) {
        return ApiResponse.ok(
                "Đã chạy đánh giá nhanh không dùng SCHP/FLORENCE",
                evaluationService.lightweight(image, studentCode, firstNonBlank(selectedMethod, uniformMethod))
        );
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
