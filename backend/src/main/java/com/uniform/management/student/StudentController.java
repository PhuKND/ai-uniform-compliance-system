package com.uniform.management.student;

import com.uniform.management.common.ApiResponse;
import com.uniform.management.security.SecurityUtils;
import com.uniform.management.student.dto.ChangePasswordRequest;
import com.uniform.management.student.dto.StudentAccountCreateRequest;
import com.uniform.management.student.dto.StudentAccountResponse;
import com.uniform.management.student.dto.StudentCreateRequest;
import com.uniform.management.student.dto.StudentResponse;
import com.uniform.management.student.dto.StudentSelfUpdateRequest;
import com.uniform.management.student.dto.StudentUpdateRequest;
import com.uniform.management.uniformschedule.dto.UniformClassResponse;
import com.uniform.management.user.UserAccount;
import com.uniform.management.user.UserAccountRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/students")
public class StudentController {

    private final StudentService studentService;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public StudentController(
            StudentService studentService,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.studentService = studentService;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ApiResponse<Page<StudentResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok("Danh sách học sinh", studentService.search(keyword, className, active, pageable));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/search")
    public ApiResponse<Page<StudentResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return list(keyword, className, active, pageable);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/classes")
    public ApiResponse<List<UniformClassResponse>> classes() {
        return ApiResponse.ok("Danh sách lớp", studentService.listClasses());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ApiResponse<StudentResponse> create(@Valid @RequestBody StudentCreateRequest request) {
        return ApiResponse.ok("Thêm học sinh thành công", StudentResponse.from(studentService.create(request)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{studentCode}")
    public ApiResponse<StudentResponse> get(@PathVariable String studentCode) {
        return ApiResponse.ok("Thông tin học sinh", StudentResponse.from(studentService.getByCode(studentCode)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{studentCode}/account")
    public ApiResponse<StudentAccountResponse> createAccount(
            @PathVariable String studentCode,
            @Valid @RequestBody StudentAccountCreateRequest request
    ) {
        return ApiResponse.ok("Tạo tài khoản học sinh thành công", studentService.createAccount(studentCode, request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{studentCode}")
    public ApiResponse<StudentResponse> update(
            @PathVariable String studentCode,
            @Valid @RequestBody StudentUpdateRequest request
    ) {
        return ApiResponse.ok(
                "Cập nhật học sinh thành công",
                StudentResponse.from(studentService.updateAsAdmin(studentCode, request, SecurityUtils.currentUser()))
        );
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{studentCode}")
    public ApiResponse<Void> delete(@PathVariable String studentCode) {
        studentService.deactivateByAdmin(studentCode);
        return ApiResponse.ok("Đã vô hiệu hóa hồ sơ học sinh", null);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @GetMapping("/me")
    public ApiResponse<StudentResponse> me() {
        return ApiResponse.ok("Hồ sơ của tôi", StudentResponse.from(SecurityUtils.currentStudent(), SecurityUtils.currentUser()));
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PatchMapping("/me")
    public ApiResponse<StudentResponse> updateMe(@Valid @RequestBody StudentSelfUpdateRequest request) {
        return ApiResponse.ok(
                "Cập nhật hồ sơ cá nhân thành công",
                StudentResponse.from(studentService.updateSelf(SecurityUtils.currentStudent(), request), SecurityUtils.currentUser())
        );
    }

    @PreAuthorize("hasRole('STUDENT')")
    @DeleteMapping("/me")
    public ApiResponse<Void> requestDeletion() {
        studentService.requestSelfDeletion(SecurityUtils.currentStudent());
        return ApiResponse.ok("Đã gửi yêu cầu xóa hồ sơ", null);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/me/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        UserAccount user = SecurityUtils.currentUser();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new com.uniform.management.common.BadRequestException("Mật khẩu hiện tại không đúng");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userAccountRepository.save(user);
        return ApiResponse.ok("Đổi mật khẩu thành công", null);
    }
}
