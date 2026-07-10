package com.uniform.management.correctionrequest;

import com.uniform.management.common.ApiResponse;
import com.uniform.management.correctionrequest.dto.CorrectionRequestResponse;
import com.uniform.management.correctionrequest.dto.ResolveCorrectionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/correction-requests")
@Validated
public class CorrectionRequestController {

    private final CorrectionRequestService correctionRequestService;

    public CorrectionRequestController(CorrectionRequestService correctionRequestService) {
        this.correctionRequestService = correctionRequestService;
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping
    public ApiResponse<CorrectionRequestResponse> create(
            @RequestParam Long evaluationHistoryId,
            @RequestParam
            @Min(value = 0, message = "Điểm trừ rèn luyện đề nghị không được âm")
            @Max(value = 100, message = "Điểm trừ rèn luyện đề nghị không được vượt quá 100")
            Integer requestedDeduction,
            @RequestParam
            @NotBlank(message = "Lý do yêu cầu là bắt buộc")
            @Size(max = 5000, message = "Lý do yêu cầu không được vượt quá 5000 ký tự")
            String reason,
            @RequestParam(required = false)
            @Size(max = 5000, message = "Ghi chú minh chứng không được vượt quá 5000 ký tự")
            String evidenceNote,
            @RequestParam(required = false) MultipartFile evidenceImage
    ) {
        return ApiResponse.ok(
                "Đã gửi yêu cầu sửa đổi",
                correctionRequestService.create(
                        evaluationHistoryId,
                        requestedDeduction,
                        reason,
                        evidenceNote,
                        evidenceImage
                )
        );
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/me")
    public ApiResponse<Page<CorrectionRequestResponse>> myRequests(@PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Yêu cầu sửa đổi của tôi", correctionRequestService.myRequests(pageable));
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/{id}/cancel")
    public ApiResponse<CorrectionRequestResponse> cancel(@PathVariable Long id) {
        return ApiResponse.ok("Đã hủy yêu cầu sửa đổi", correctionRequestService.cancel(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ApiResponse<Page<CorrectionRequestResponse>> all(@PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Danh sách yêu cầu sửa đổi", correctionRequestService.all(pageable));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/approve")
    public ApiResponse<CorrectionRequestResponse> approve(
            @PathVariable Long id,
            @Valid @RequestBody ResolveCorrectionRequest request
    ) {
        return ApiResponse.ok("Đã đồng ý yêu cầu sửa đổi", correctionRequestService.approve(id, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/reject")
    public ApiResponse<CorrectionRequestResponse> reject(
            @PathVariable Long id,
            @Valid @RequestBody ResolveCorrectionRequest request
    ) {
        return ApiResponse.ok("Đã từ chối yêu cầu sửa đổi", correctionRequestService.reject(id, request));
    }
}
