package com.uniform.management.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface EvaluationRunRepository extends JpaRepository<EvaluationRun, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from EvaluationRun run where run.id = :id")
    Optional<EvaluationRun> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select run.id from EvaluationRun run
            where (run.method1Image is null and run.method1ProcessedImagePath is not null)
               or (run.method2Image is null and run.method2ProcessedImagePath is not null)
            order by run.id
            """)
    List<Long> findIdsMissingProcessedImages();
}
