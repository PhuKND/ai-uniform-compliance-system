package com.uniform.management.facedata;

import com.uniform.management.student.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FaceDataRecordRepository extends JpaRepository<FaceDataRecord, Long> {
    List<FaceDataRecord> findByStudentOrderByCreatedAtDesc(Student student);

    Optional<FaceDataRecord> findTopByStudentOrderByCreatedAtDesc(Student student);
}
