package com.uniform.management.evaluation;

import com.uniform.management.common.ApiResponse;
import com.uniform.management.evaluation.dto.ChooseOfficialRequest;
import com.uniform.management.evaluation.dto.EvaluationCompareResponse;
import com.uniform.management.evaluationhistory.dto.EvaluationHistoryResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/evaluations")
@PreAuthorize("hasRole('ADMIN')")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping({"/run", "/compare"})
    public ApiResponse<EvaluationCompareResponse> compare(
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String studentCode
    ) {
        return ApiResponse.ok("So sánh kết quả AI", evaluationService.compare(image, studentCode));
    }

    @PostMapping({"/compare/start", "/run/start"})
    public ApiResponse<EvaluationCompareResponse> startCompare(
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String studentCode
    ) {
        return ApiResponse.ok("Đã bắt đầu so sánh AI", evaluationService.startCompare(image, studentCode));
    }

    @GetMapping({"/compare/status/{jobId}", "/run/status/{jobId}"})
    public ApiResponse<EvaluationCompareResponse> compareStatus(@PathVariable Long jobId) {
        return ApiResponse.ok("Trạng thái so sánh AI", evaluationService.compareStatus(jobId));
    }

    @PostMapping("/{runId}/choose-official")
    public ApiResponse<EvaluationHistoryResponse> chooseOfficial(
            @PathVariable Long runId,
            @Valid @RequestBody ChooseOfficialRequest request
    ) {
        return ApiResponse.ok("Đã lưu kết quả đánh giá chính thức", evaluationService.chooseOfficial(runId, request));
    }
}
