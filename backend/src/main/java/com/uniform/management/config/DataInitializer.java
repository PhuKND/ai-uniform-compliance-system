package com.uniform.management.config;

import com.uniform.management.common.enums.Role;
import com.uniform.management.user.UserAccount;
import com.uniform.management.user.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner seedAdmin(
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.admin.email}") String adminEmail,
            @Value("${app.admin.password}") String adminPassword
    ) {
        return args -> {
            if (userAccountRepository.existsByRole(Role.ADMIN)) {
                return;
            }
            if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
                throw new IllegalStateException("UNIFORM_ADMIN_EMAIL and UNIFORM_ADMIN_PASSWORD must be set before seeding the first admin account");
            }
            UserAccount admin = new UserAccount();
            admin.setEmail(adminEmail.trim().toLowerCase());
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setRole(Role.ADMIN);
            admin.setEnabled(true);
            userAccountRepository.save(admin);
        };
    }
}
