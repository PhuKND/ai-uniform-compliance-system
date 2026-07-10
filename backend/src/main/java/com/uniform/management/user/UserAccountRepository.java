package com.uniform.management.user;

import com.uniform.management.common.enums.Role;
import com.uniform.management.student.Student;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    @EntityGraph(attributePaths = "student")
    Optional<UserAccount> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = "student")
    Optional<UserAccount> findByEmailIgnoreCaseOrUsernameIgnoreCase(String email, String username);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByStudent(Student student);

    boolean existsByRole(Role role);
}
