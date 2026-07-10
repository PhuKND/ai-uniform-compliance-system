package com.uniform.management.facedata.dto;

import com.uniform.management.facedata.FaceDataRecord;
import com.uniform.management.student.Student;

import java.time.Instant;

public record FaceDataStatusResponse(
        String studentCode,
        String fullName,
        String faceDataId,
        boolean enrolled,
        int sampleCount,
        Long lastRecordId,
        Instant lastSyncedAt
) {
    public static FaceDataStatusResponse of(Student student, FaceDataRecord record) {
        return new FaceDataStatusResponse(
                student.getStudentCode(),
                student.getFullName(),
                student.getFaceDataId(),
                record != null && record.isEnrolled(),
                record == null ? 0 : record.getSampleCount(),
                record == null ? null : record.getId(),
                record == null ? null : record.getLastSyncedAt()
        );
    }
}
