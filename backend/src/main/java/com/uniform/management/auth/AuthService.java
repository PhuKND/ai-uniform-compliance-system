package com.uniform.management.auth;

import com.uniform.management.auth.dto.AuthResponse;
import com.uniform.management.auth.dto.LoginRequest;
import com.uniform.management.auth.dto.RegisterRequest;
import com.uniform.management.common.BadRequestException;
import com.uniform.management.common.enums.Role;
import com.uniform.management.security.JwtService;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentService;
import com.uniform.management.student.dto.StudentCreateRequest;
import com.uniform.management.student.dto.StudentResponse;
import com.uniform.management.user.UserAccount;
import com.uniform.management.user.UserAccountRepository;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final StudentService studentService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(
            UserAccountRepository userAccountRepository,
            StudentService studentService,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService
    ) {
        this.userAccountRepository = userAccountRepository;
        this.studentService = studentService;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse registerStudent(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (identifierInUse(email)) {
            throw new BadRequestException("Email đã được sử dụng");
        }
        Student student = studentService.create(new StudentCreateRequest(
                request.fullName(),
                request.gender(),
                request.dateOfBirth(),
                request.className(),
                request.schoolYear(),
                request.phone(),
                request.email(),
                request.address()
        ));

        UserAccount user = new UserAccount();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.STUDENT);
        user.setEnabled(true);
        user.setStudent(student);
        student.setUserAccount(user);
        userAccountRepository.save(user);

        return response(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String identifier = normalizeIdentifier(request.email());
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(identifier, request.password())
        );
        UserAccount user = userAccountRepository.findByEmailIgnoreCaseOrUsernameIgnoreCase(identifier, identifier)
                .orElseThrow(() -> new BadRequestException("Email/tên đăng nhập hoặc mật khẩu không đúng"));
        return response(user);
    }

    private AuthResponse response(UserAccount user) {
        StudentResponse student = user.getStudent() == null ? null : StudentResponse.from(user.getStudent(), user);
        return new AuthResponse(
                "Bearer",
                jwtService.generateToken(user),
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                student
        );
    }

    private boolean identifierInUse(String identifier) {
        return userAccountRepository.findByEmailIgnoreCaseOrUsernameIgnoreCase(identifier, identifier).isPresent();
    }

    private String normalizeEmail(String value) {
        return normalizeIdentifier(value).toLowerCase();
    }

    private String normalizeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BadRequestException("Vui lòng nhập email hoặc tên đăng nhập");
        }
        return value.trim();
    }
}
