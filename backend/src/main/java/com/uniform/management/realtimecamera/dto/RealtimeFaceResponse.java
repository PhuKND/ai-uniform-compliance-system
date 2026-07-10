package com.uniform.management.realtimecamera.dto;

import java.util.List;

public record RealtimeFaceResponse(
        List<Double> bbox,
        Double confidence
) {
}
