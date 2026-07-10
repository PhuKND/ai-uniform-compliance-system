package com.uniform.management.correctionrequest;

import com.uniform.management.common.enums.CorrectionStatus;
import com.uniform.management.student.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface CorrectionRequestRepository extends JpaRepository<CorrectionRequest, Long> {
    Page<CorrectionRequest> findByStudentOrderByCreatedAtDesc(Student student, Pageable pageable);

    Page<CorrectionRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByStudentAndEvaluationHistoryIdAndStatus(
            Student student,
            Long evaluationHistoryId,
            CorrectionStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CorrectionRequest c where c.id = :id")
    Optional<CorrectionRequest> findByIdForUpdate(@Param("id") Long id);

    @Query("select c.status, count(c) from CorrectionRequest c group by c.status")
    List<Object[]> countByStatus();

    @Query("select c.status, count(c) from CorrectionRequest c where c.student = :student group by c.status")
    List<Object[]> countByStatusForStudent(Student student);

    long countByStatus(CorrectionStatus status);
}
