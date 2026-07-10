package com.uniform.management.evaluationhistory;

import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.student.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface EvaluationHistoryRepository extends JpaRepository<EvaluationHistory, Long>, JpaSpecificationExecutor<EvaluationHistory> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EvaluationHistory e where e.id = :id")
    Optional<EvaluationHistory> findByIdForUpdate(@Param("id") Long id);

    Page<EvaluationHistory> findByStudent(Student student, Pageable pageable);

    long countByStudent(Student student);

    List<EvaluationHistory> findByStudentOrderByCreatedAtAsc(Student student);

    long countByComplianceStatus(ComplianceStatus complianceStatus);

    @Query("select e.complianceStatus, count(e) from EvaluationHistory e group by e.complianceStatus")
    List<Object[]> countEvaluationsByStatus();

    @Query("select e.complianceStatus, count(e) from EvaluationHistory e where e.student = :student group by e.complianceStatus")
    List<Object[]> countEvaluationsByStatusForStudent(@Param("student") Student student);

    @Query("select avg(e.finalScore) from EvaluationHistory e where e.finalScore is not null")
    Double averageFinalScore();

    @Query("select avg(e.finalScore) from EvaluationHistory e where e.student = :student and e.finalScore is not null")
    Double averageFinalScoreForStudent(@Param("student") Student student);

    @Query("select coalesce(sum(e.deductedPoints), 0) from EvaluationHistory e")
    Long sumDeductedPoints();

    @Query("select coalesce(sum(e.deductedPoints), 0) from EvaluationHistory e where e.student = :student")
    Long sumDeductedPointsForStudent(@Param("student") Student student);

    @Query("select count(v) from EvaluationHistory e join e.violationTypes v")
    long countTotalViolations();

    @Query("select v, count(v) from EvaluationHistory e join e.violationTypes v group by v order by count(v) desc")
    List<Object[]> countViolationsByType();

    @Query("select v, count(v) from EvaluationHistory e join e.violationTypes v where e.student = :student group by v order by count(v) desc")
    List<Object[]> countViolationsByTypeForStudent(@Param("student") Student student);

    @Query("select e.classNameSnapshot, count(e) from EvaluationHistory e where e.classNameSnapshot is not null group by e.classNameSnapshot")
    List<Object[]> countEvaluationsByClass();

    @Query("select e.classNameSnapshot, avg(e.finalScore) from EvaluationHistory e where e.classNameSnapshot is not null and e.finalScore is not null group by e.classNameSnapshot")
    List<Object[]> averageFinalScoreByClass();

    @Query("select e.classNameSnapshot, coalesce(sum(e.deductedPoints), 0) from EvaluationHistory e where e.classNameSnapshot is not null group by e.classNameSnapshot")
    List<Object[]> sumDeductedPointsByClass();

    @Query("select e.selectedMethod, count(e) from EvaluationHistory e group by e.selectedMethod")
    List<Object[]> countEvaluationsByMethod();

    @Query("""
            select e.studentCodeSnapshot, e.studentNameSnapshot, count(v)
            from EvaluationHistory e join e.violationTypes v
            group by e.studentCodeSnapshot, e.studentNameSnapshot
            order by count(v) desc
            """)
    List<Object[]> studentsWithMostViolations(Pageable pageable);

    @Query("""
            select e from EvaluationHistory e
            where (:studentCode is null or :studentCode = '' or lower(e.studentCodeSnapshot) like lower(concat('%', :studentCode, '%')))
              and (:studentId is null or e.student.id = :studentId)
              and (:studentName is null or :studentName = '' or lower(e.studentNameSnapshot) like lower(concat('%', :studentName, '%')))
              and (:className is null or :className = '' or e.classNameSnapshot = :className)
              and (:method is null or e.selectedMethod = :method)
              and (:status is null or e.complianceStatus = :status)
              and (:createdBy is null or :createdBy = '' or lower(e.createdBy.email) like lower(concat('%', :createdBy, '%')))
              and (:minDeducted is null or e.deductedPoints >= :minDeducted)
              and (:maxDeducted is null or e.deductedPoints <= :maxDeducted)
              and (:fromDate is null or e.createdAt >= :fromDate)
              and (:toDate is null or e.createdAt <= :toDate)
            """)
    Page<EvaluationHistory> search(
            @Param("studentCode") String studentCode,
            @Param("studentId") Long studentId,
            @Param("studentName") String studentName,
            @Param("className") String className,
            @Param("method") EvaluationMethod method,
            @Param("status") ComplianceStatus status,
            @Param("createdBy") String createdBy,
            @Param("minDeducted") Integer minDeducted,
            @Param("maxDeducted") Integer maxDeducted,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate,
            Pageable pageable
    );

    @Query("""
            select e from EvaluationHistory e join e.violationTypes v
            where e.student = :student and (:violationType is null or :violationType = '' or v = :violationType)
            """)
    Page<EvaluationHistory> findOwnByViolationType(
            @Param("student") Student student,
            @Param("violationType") String violationType,
            Pageable pageable
    );
}
