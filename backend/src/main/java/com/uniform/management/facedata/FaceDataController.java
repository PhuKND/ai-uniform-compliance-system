package com.uniform.management.facedata;

import com.uniform.management.common.ApiResponse;
import com.uniform.management.facedata.dto.FaceDataStatusResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/face-data")
@PreAuthorize("hasRole('ADMIN')")
public class FaceDataController {

    private final FaceDataService faceDataService;

    public FaceDataController(FaceDataService faceDataService) {
        this.faceDataService = faceDataService;
    }

    @GetMapping
    public ApiResponse<List<FaceDataStatusResponse>> allStatuses() {
        return ApiResponse.ok("Trạng thái dữ liệu khuôn mặt", faceDataService.allStatuses());
    }

    @GetMapping("/{studentCode}")
    public ApiResponse<FaceDataStatusResponse> status(@PathVariable String studentCode) {
        return ApiResponse.ok("Trạng thái dữ liệu khuôn mặt", faceDataService.status(studentCode));
    }

    @PostMapping("/{studentCode}")
    public ApiResponse<FaceDataStatusResponse> enroll(
            @PathVariable String studentCode,
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String sampleLabel,
            @RequestParam(defaultValue = "false") boolean additionalSample
    ) {
        return ApiResponse.ok(
                "Thêm dữ liệu khuôn mặt thành công",
                faceDataService.enroll(studentCode, image, sampleLabel, additionalSample)
        );
    }

    @PostMapping
    public ApiResponse<FaceDataStatusResponse> enrollByParam(
            @RequestParam String studentCode,
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String sampleLabel,
            @RequestParam(defaultValue = "false") boolean additionalSample
    ) {
        return enroll(studentCode, image, sampleLabel, additionalSample);
    }

    @PutMapping("/{studentCode}")
    public ApiResponse<FaceDataStatusResponse> reEnroll(
            @PathVariable String studentCode,
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String sampleLabel
    ) {
        return ApiResponse.ok(
                "Cập nhật dữ liệu khuôn mặt thành công",
                faceDataService.reEnroll(studentCode, image, sampleLabel)
        );
    }

    @DeleteMapping("/{studentCode}")
    public ApiResponse<FaceDataStatusResponse> delete(@PathVariable String studentCode) {
        return ApiResponse.ok("Xóa dữ liệu khuôn mặt thành công", faceDataService.delete(studentCode));
    }
}
