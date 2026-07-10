package com.uniform.management.realtimecamera.dto;

public record RealtimeIdentityResponse(
        boolean matched,
        Long studentId,
        String studentCode,
        String fullName,
        String className,
        Double confidence,
        String label
) {
    public static RealtimeIdentityResponse unknown(Double confidence) {
        return new RealtimeIdentityResponse(false, null, null, null, null, confidence, "Unknown person");
    }
}
