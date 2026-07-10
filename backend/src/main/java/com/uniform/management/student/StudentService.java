package com.uniform.management.student;

import com.uniform.management.common.BadRequestException;
import com.uniform.management.common.ResourceNotFoundException;
import com.uniform.management.common.enums.Role;
import com.uniform.management.facedata.FaceDataRecord;
import com.uniform.management.facedata.FaceDataRecordRepository;
import com.uniform.management.student.dto.StudentAccountCreateRequest;
import com.uniform.management.student.dto.StudentAccountResponse;
import com.uniform.management.student.dto.StudentCreateRequest;
import com.uniform.management.student.dto.StudentResponse;
import com.uniform.management.student.dto.StudentSelfUpdateRequest;
import com.uniform.management.student.dto.StudentUpdateRequest;
import com.uniform.management.uniformschedule.dto.UniformClassResponse;
import com.uniform.management.uniformai.UniformAiClient;
import com.uniform.management.user.UserAccount;
import com.uniform.management.user.UserAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDate;
import java.util.List;

@Service
public class StudentService {

    private final StudentRepository studentRepository;
    private final StudentCodeGenerator codeGenerator;
    private final MoralityService moralityService;
    private final UniformAiClient uniformAiClient;
    private final FaceDataRecordRepository faceDataRecordRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public StudentService(
            StudentRepository studentRepository,
            StudentCodeGenerator codeGenerator,
            MoralityService moralityService,
            UniformAiClient uniformAiClient,
            FaceDataRecordRepository faceDataRecordRepository,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.studentRepository = studentRepository;
        this.codeGenerator = codeGenerator;
        this.moralityService = moralityService;
        this.uniformAiClient = uniformAiClient;
        this.faceDataRecordRepository = faceDataRecordRepository;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Student create(StudentCreateRequest request) {
        Student student = new Student();
        String code = codeGenerator.generate(request.fullName());
        student.setStudentCode(code);
        student.setFaceDataId(code);
        student.setFullName(request.fullName().trim());
        applyCommonFields(student, request.gender(), request.dateOfBirth(), request.className(), request.schoolYear(),
                request.phone(), request.email(), request.address());
        student.setMoralityScore(100);
        student.setMoralityLevel(moralityService.calculateLevel(100));
        return studentRepository.save(student);
    }

    @Transactional(readOnly = true)
    public Student getByCode(String studentCode) {
        return studentRepository.findByStudentCode(studentCode)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy học sinh: " + studentCode));
    }

    @Transactional(readOnly = true)
    public Page<StudentResponse> search(String keyword, String className, Boolean active, Pageable pageable) {
        return studentRepository.search(keyword, className, active, pageable).map(StudentResponse::from);
    }

    @Transactional(readOnly = true)
    public List<UniformClassResponse> listClasses() {
        return studentRepository.countStudentsByClass().stream()
                .map(row -> new UniformClassResponse((String) row[0], (String) row[0], ((Number) row[1]).longValue()))
                .filter(row -> row.className() != null && !row.className().isBlank())
                .sorted((left, right) -> left.className().compareToIgnoreCase(right.className()))
                .toList();
    }

    @Transactional
    public StudentAccountResponse createAccount(String studentCode, StudentAccountCreateRequest request) {
        Student student = getByCode(studentCode);
        if (student.getUserAccount() != null || userAccountRepository.existsByStudent(student)) {
            throw new BadRequestException("Học sinh này đã có tài khoản đăng nhập");
        }

        String username = normalizeUsername(request.username());
        String email = normalizeEmail(request.email());
        validateUniqueIdentifier(username, "Tên đăng nhập đã được sử dụng");
        validateUniqueIdentifier(email, "Email đã được sử dụng");
        validatePassword(request.password(), request.confirmPassword());

        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.STUDENT);
        user.setEnabled(student.isActive());
        user.setStudent(student);
        student.setUserAccount(user);
        user = userAccountRepository.save(user);

        return StudentAccountResponse.from(user);
    }

    @Transactional
    public Student updateAsAdmin(String studentCode, StudentUpdateRequest request, UserAccount admin) {
        Student student = getByCode(studentCode);
        String oldCode = student.getStudentCode();
        boolean nameChanged = StringUtils.hasText(request.fullName())
                && !request.fullName().trim().equalsIgnoreCase(student.getFullName());

        if (nameChanged) {
            String newPrefix = codeGenerator.prefixFromFullName(request.fullName());
            String oldPrefix = codeGenerator.prefixFromFullName(student.getFullName());
            String newCode = oldPrefix.equals(newPrefix)
                    ? oldCode
                    : codeGenerator.generate(request.fullName(), oldCode);
            if (!newCode.equals(oldCode) && hasEnrolledFace(student)) {
                try {
                    uniformAiClient.renameFaceData(oldCode, newCode, request.fullName().trim());
                } catch (WebClientResponseException.NotFound ignored) {
                    // No enrolled face data exists in the AI service; DB metadata still moves to the new code.
                }
            }
            student.setStudentCode(newCode);
            student.setFaceDataId(newCode);
            student.setFullName(request.fullName().trim());
        }

        applyCommonFields(student, request.gender(), request.dateOfBirth(), request.className(), request.schoolYear(),
                request.phone(), request.email(), request.address());
        if (request.moralityScore() != null) {
            moralityService.setScore(student, request.moralityScore(), "Admin cập nhật điểm đạo đức", null, admin);
        }
        if (request.active() != null) {
            student.setActive(request.active());
            if (student.getUserAccount() != null) {
                student.getUserAccount().setEnabled(request.active());
            }
        }
        return studentRepository.save(student);
    }

    @Transactional
    public Student updateSelf(Student student, StudentSelfUpdateRequest request) {
        if (request.phone() != null) {
            student.setPhone(request.phone());
        }
        if (request.email() != null) {
            student.setEmail(request.email());
        }
        if (request.address() != null) {
            student.setAddress(request.address());
        }
        return studentRepository.save(student);
    }

    @Transactional
    public void requestSelfDeletion(Student student) {
        student.setDeletionRequested(true);
        studentRepository.save(student);
    }

    @Transactional
    public void deactivateByAdmin(String studentCode) {
        Student student = getByCode(studentCode);
        student.setActive(false);
        if (student.getUserAccount() != null) {
            student.getUserAccount().setEnabled(false);
        }
        studentRepository.save(student);
    }

    private void validateUniqueIdentifier(String identifier, String message) {
        if (userAccountRepository.findByEmailIgnoreCaseOrUsernameIgnoreCase(identifier, identifier).isPresent()) {
            throw new BadRequestException(message);
        }
    }

    private void validatePassword(String password, String confirmPassword) {
        if (!StringUtils.hasText(password)) {
            throw new BadRequestException("Vui lòng nhập mật khẩu");
        }
        if (password.length() < 6) {
            throw new BadRequestException("Mật khẩu cần có ít nhất 6 ký tự");
        }
        if (StringUtils.hasText(confirmPassword) && !password.equals(confirmPassword)) {
            throw new BadRequestException("Mật khẩu xác nhận không khớp");
        }
    }

    private String normalizeUsername(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BadRequestException("Vui lòng nhập tên đăng nhập");
        }
        return value.trim().toLowerCase();
    }

    private String normalizeEmail(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BadRequestException("Vui lòng nhập email đăng nhập");
        }
        return value.trim().toLowerCase();
    }

    private boolean hasEnrolledFace(Student student) {
        return faceDataRecordRepository.findTopByStudentOrderByCreatedAtDesc(student)
                .map(FaceDataRecord::isEnrolled)
                .orElse(false);
    }

    private void applyCommonFields(
            Student student,
            com.uniform.management.common.enums.Gender gender,
            java.time.LocalDate dateOfBirth,
            String className,
            String schoolYear,
            String phone,
            String email,
            String address
    ) {
        if (gender != null) {
            student.setGender(gender);
        }
        if (dateOfBirth != null) {
            if (dateOfBirth.isAfter(LocalDate.now())) {
                throw new BadRequestException("Ng\u00e0y sinh kh\u00f4ng \u0111\u01b0\u1ee3c \u1edf t\u01b0\u01a1ng lai");
            }
            student.setDateOfBirth(dateOfBirth);
        }
        if (className != null) {
            student.setClassName(className.trim());
        }
        if (schoolYear != null) {
            student.setSchoolYear(schoolYear.trim());
        }
        if (phone != null) {
            student.setPhone(phone.trim());
        }
        if (email != null) {
            student.setEmail(email.trim().toLowerCase());
        }
        if (address != null) {
            student.setAddress(address.trim());
        }
    }
}
