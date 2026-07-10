package com.uniform.management.student;

import com.uniform.management.common.enums.MoralityLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long>, JpaSpecificationExecutor<Student> {

    boolean existsByStudentCode(String studentCode);

    @EntityGraph(attributePaths = "userAccount")
    Optional<Student> findByStudentCode(String studentCode);

    @EntityGraph(attributePaths = "userAccount")
    Optional<Student> findByFaceDataId(String faceDataId);

    Optional<Student> findByEmailIgnoreCase(String email);

    @Query("select s.studentCode from Student s where s.studentCode like concat(:prefix, '%')")
    List<String> findCodesWithPrefix(@Param("prefix") String prefix);

    @EntityGraph(attributePaths = "userAccount")
    @Query("""
            select s from Student s
            where (:keyword is null or :keyword = ''
                or lower(s.studentCode) like lower(concat('%', :keyword, '%'))
                or lower(s.fullName) like lower(concat('%', :keyword, '%'))
                or lower(s.className) like lower(concat('%', :keyword, '%'))
                or lower(s.email) like lower(concat('%', :keyword, '%')))
              and (:className is null or :className = '' or s.className = :className)
              and (:active is null or s.active = :active)
            """)
    Page<Student> search(
            @Param("keyword") String keyword,
            @Param("className") String className,
            @Param("active") Boolean active,
            Pageable pageable
    );

    long countByMoralityLevel(MoralityLevel moralityLevel);

    long countByClassName(String className);

    @Query("select s.className, count(s) from Student s where s.className is not null group by s.className")
    List<Object[]> countStudentsByClass();

    @Query("select s.moralityLevel, count(s) from Student s group by s.moralityLevel")
    List<Object[]> countStudentsByMoralityLevel();

    @Query("select s.className, avg(s.moralityScore) from Student s where s.className is not null group by s.className")
    List<Object[]> averageMoralityScoreByClass();

    List<Student> findTop10ByMoralityScoreLessThanEqualOrderByMoralityScoreAsc(int score);
}
