package com.uniform.management.realtimecamera.dto;

public record RealtimeKeypointResponse(
        String name,
        double x,
        double y,
        Double confidence
) {
}
