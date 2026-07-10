package com.uniform.management.evaluationhistory;

import com.uniform.management.common.ApiResponse;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.evaluationhistory.dto.EvaluationHistoryResponse;
import com.uniform.management.evaluationhistory.dto.EvaluationHistoryUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/evaluation-history")
public class EvaluationHistoryController {

    private final EvaluationHistoryService evaluationHistoryService;

    public EvaluationHistoryController(EvaluationHistoryService evaluationHistoryService) {
        this.evaluationHistoryService = evaluationHistoryService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping({"", "/search"})
    public ApiResponse<Page<EvaluationHistoryResponse>> search(
            @RequestParam(required = false) String studentCode,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) String studentName,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) EvaluationMethod method,
            @RequestParam(required = false) ComplianceStatus status,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) Integer minDeducted,
            @RequestParam(required = false) Integer maxDeducted,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toDate,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(
                "Lịch sử đánh giá đồng phục",
                evaluationHistoryService.search(
                        studentCode,
                        studentId,
                        studentName,
                        className,
                        method,
                        status,
                        createdBy,
                        minDeducted,
                        maxDeducted,
                        fromDate,
                        toDate,
                        pageable
                )
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<EvaluationHistoryResponse> get(@PathVariable Long id) {
        return ApiResponse.ok("Chi tiết lịch sử đánh giá", evaluationHistoryService.getForAdmin(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<EvaluationHistoryResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody EvaluationHistoryUpdateRequest request
    ) {
        return ApiResponse.ok("Cập nhật lịch sử đánh giá thành công", evaluationHistoryService.update(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        evaluationHistoryService.delete(id);
        return ApiResponse.ok("Xóa lịch sử đánh giá thành công", null);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/me")
    public ApiResponse<Page<EvaluationHistoryResponse>> ownHistory(
            @RequestParam(required = false) String violationType,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok("Lịch sử đánh giá của tôi", evaluationHistoryService.ownHistory(violationType, pageable));
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/me/{id}")
    public ApiResponse<EvaluationHistoryResponse> ownHistoryDetail(@PathVariable Long id) {
        return ApiResponse.ok("Chi tiết lịch sử đánh giá của tôi", evaluationHistoryService.getOwn(id));
    }
}
