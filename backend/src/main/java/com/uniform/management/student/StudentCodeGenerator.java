package com.uniform.management.student;

import com.uniform.management.common.BadRequestException;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StudentCodeGenerator {

    private static final Pattern NUMBERED_CODE = Pattern.compile("^([A-Z0-9]+)(\\d{3})$");
    private final StudentRepository studentRepository;

    public StudentCodeGenerator(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    public String generate(String fullName) {
        return generate(fullName, null);
    }

    public String generate(String fullName, String currentCodeToIgnore) {
        String prefix = prefixFromFullName(fullName);
        int next = studentRepository.findCodesWithPrefix(prefix).stream()
                .filter(code -> currentCodeToIgnore == null || !code.equalsIgnoreCase(currentCodeToIgnore))
                .map(code -> NUMBERED_CODE.matcher(code.toUpperCase()))
                .filter(Matcher::matches)
                .filter(matcher -> matcher.group(1).equals(prefix))
                .map(matcher -> Integer.parseInt(matcher.group(2)))
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
        if (next > 999) {
            throw new BadRequestException("Đã vượt quá giới hạn 999 mã cho họ/tên cuối: " + prefix);
        }
        return prefix + String.format("%03d", next);
    }

    public String prefixFromFullName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            throw new BadRequestException("Họ tên học sinh là bắt buộc");
        }
        String[] parts = fullName.trim().split("\\s+");
        String lastWord = parts[parts.length - 1];
        String normalized = Normalizer.normalize(lastWord, Normalizer.Form.NFD)
                .replace("Đ", "D")
                .replace("đ", "d")
                .replaceAll("\\p{M}", "")
                .toUpperCase()
                .replaceAll("[^A-Z0-9]", "");
        if (normalized.isBlank()) {
            throw new BadRequestException("Không thể tạo mã học sinh từ họ tên: " + fullName);
        }
        return normalized;
    }
}
