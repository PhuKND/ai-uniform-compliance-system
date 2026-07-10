package com.uniform.management.security;

import com.uniform.management.common.ResourceNotFoundException;
import com.uniform.management.student.Student;
import com.uniform.management.user.UserAccount;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static UserAccount currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserDetails details)) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng đăng nhập");
        }
        return details.getUser();
    }

    public static Student currentStudent() {
        Student student = currentUser().getStudent();
        if (student == null) {
            throw new ResourceNotFoundException("Tài khoản chưa liên kết học sinh");
        }
        return student;
    }
}
