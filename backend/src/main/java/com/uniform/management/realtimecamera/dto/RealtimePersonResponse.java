package com.uniform.management.realtimecamera.dto;

import java.util.List;

public record RealtimePersonResponse(
        List<Double> bbox,
        Double confidence,
        List<RealtimeKeypointResponse> keypoints,
        List<RealtimePoseLinkResponse> skeleton
) {
}
