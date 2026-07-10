package com.uniform.management.realtimecamera.dto;

import java.util.List;

public record RealtimeUniformDetectionResponse(
        String className,
        Double confidence,
        List<Double> bbox
) {
}
