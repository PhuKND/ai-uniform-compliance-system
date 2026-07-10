package com.uniform.management.student;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MoralityScoreLogRepository extends JpaRepository<MoralityScoreLog, Long> {
    List<MoralityScoreLog> findByStudentOrderByCreatedAtAsc(Student student);

    List<MoralityScoreLog> findByStudentOrderByCreatedAtDesc(Student student, Pageable pageable);
}
