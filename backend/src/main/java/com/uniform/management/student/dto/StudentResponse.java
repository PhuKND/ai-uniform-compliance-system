package com.uniform.management.student.dto;

import com.uniform.management.common.enums.Gender;
import com.uniform.management.student.Student;
import com.uniform.management.user.UserAccount;

import java.time.Instant;
import java.time.LocalDate;

public record StudentResponse(
        Long id,
        String studentCode,
        String faceDataId,
        String fullName,
        Gender gender,
        LocalDate dateOfBirth,
        Integer age,
        String className,
        String schoolYear,
        String phone,
        String email,
        String address,
        int moralityScore,
        String moralityLevel,
        String moralityLevelCode,
        boolean active,
        boolean deletionRequested,
        boolean hasAccount,
        String accountUsername,
        String accountEmail,
        Boolean accountEnabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static StudentResponse from(Student student) {
        return from(student, student.getUserAccount());
    }

    public static StudentResponse from(Student student, UserAccount account) {
        return new StudentResponse(
                student.getId(),
                student.getStudentCode(),
                student.getFaceDataId(),
                student.getFullName(),
                student.getGender(),
                student.getDateOfBirth(),
                student.getAge(),
                student.getClassName(),
                student.getSchoolYear(),
                student.getPhone(),
                student.getEmail(),
                student.getAddress(),
                student.getMoralityScore(),
                student.getMoralityLevel().getVietnameseLabel(),
                student.getMoralityLevel().name(),
                student.isActive(),
                student.isDeletionRequested(),
                account != null,
                account == null ? null : account.getUsername(),
                account == null ? null : account.getEmail(),
                account == null ? null : account.isEnabled(),
                student.getCreatedAt(),
                student.getUpdatedAt()
        );
    }
}
