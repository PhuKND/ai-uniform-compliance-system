package com.uniform.management.realtimecamera.dto;

import java.util.List;

public record RealtimeCameraAnalysisResponse(
        boolean success,
        String message,
        Integer frameWidth,
        Integer frameHeight,
        Long processingTimeMs,
        RealtimePersonResponse selectedPerson,
        RealtimeIdentityResponse identity,
        RealtimeFaceResponse face,
        List<RealtimeUniformDetectionResponse> uniformDetections,
        String pipeline
) {
}
