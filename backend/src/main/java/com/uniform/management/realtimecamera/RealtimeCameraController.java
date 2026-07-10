package com.uniform.management.realtimecamera;

import com.uniform.management.common.ApiResponse;
import com.uniform.management.realtimecamera.dto.RealtimeCameraAnalysisResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/realtime-camera")
@PreAuthorize("hasRole('ADMIN')")
public class RealtimeCameraController {

    private final RealtimeCameraService realtimeCameraService;

    public RealtimeCameraController(RealtimeCameraService realtimeCameraService) {
        this.realtimeCameraService = realtimeCameraService;
    }

    @PostMapping("/analyze-frame")
    public ApiResponse<RealtimeCameraAnalysisResponse> analyzeFrame(
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) Integer frameWidth,
            @RequestParam(required = false) Integer frameHeight
    ) {
        return ApiResponse.ok("Real-time camera frame analyzed", realtimeCameraService.analyzeFrame(image, frameWidth, frameHeight));
    }
}
