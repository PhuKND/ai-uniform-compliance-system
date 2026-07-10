package com.uniform.management.evaluationhistory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.common.enums.Role;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.user.UserAccount;
import com.uniform.management.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvaluationHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private EvaluationHistoryRepository evaluationHistoryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @WithMockUser(roles = "ADMIN")
    void searchSerializesViolationTypesAfterTransaction() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String studentCode = "HIST" + suffix.substring(Math.max(0, suffix.length() - 8));

        Student student = new Student();
        student.setStudentCode(studentCode);
        student.setFaceDataId(studentCode);
        student.setFullName("Nguyen Van History");
        student.setClassName("7A1");
        student = studentRepository.save(student);

        UserAccount admin = new UserAccount();
        admin.setEmail("history-admin-" + suffix + "@test.local");
        admin.setPasswordHash("unused");
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        admin = userAccountRepository.save(admin);

        EvaluationHistory history = new EvaluationHistory();
        history.setStudent(student);
        history.setStudentCodeSnapshot(student.getStudentCode());
        history.setStudentNameSnapshot(student.getFullName());
        history.setClassNameSnapshot(student.getClassName());
        history.setSelectedMethod(EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);
        history.setComplianceStatus(ComplianceStatus.NON_COMPLIANT);
        history.setViolationTypes(new LinkedHashSet<>(List.of("MISSING_RED_SCARF", "SHIRT_NOT_TUCKED")));
        history.setViolationSummary("Missing red scarf; shirt not tucked");
        history.setFinalScore(90);
        history.setDeductedPoints(10);
        history.setCreatedBy(admin);
        evaluationHistoryRepository.save(history);

        String response = mockMvc.perform(get("/api/evaluation-history")
                        .param("page", "0")
                        .param("size", "20")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode violationTypes = objectMapper.readTree(response)
                .path("data")
                .path("content")
                .get(0)
                .path("violationTypes");

        assertThat(StreamSupport.stream(violationTypes.spliterator(), false).map(JsonNode::asText))
                .containsExactly("MISSING_RED_SCARF", "SHIRT_NOT_TUCKED");
    }

    @Test
    void studentCanOnlyOpenOwnHistoryDetail() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String ownCode = "OWN" + suffix.substring(Math.max(0, suffix.length() - 8));
        String otherCode = "OTH" + suffix.substring(Math.max(0, suffix.length() - 8));

        Student ownStudent = saveStudent(ownCode, "Nguyen Van Own");
        Student otherStudent = saveStudent(otherCode, "Nguyen Van Other");

        UserAccount admin = saveUser("history-admin-own-" + suffix + "@test.local", null, Role.ADMIN, null);
        String studentEmail = "history-student-" + suffix + "@test.local";
        saveUser(studentEmail, "history-student-" + suffix, Role.STUDENT, ownStudent);

        EvaluationHistory ownHistory = saveHistory(ownStudent, admin, "MISSING_RED_SCARF");
        EvaluationHistory otherHistory = saveHistory(otherStudent, admin, "SHIRT_NOT_TUCKED");

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "123456"}
                                """.formatted(studentEmail)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = objectMapper.readTree(loginBody).path("data").path("accessToken").asText();

        String ownResponse = mockMvc.perform(get("/api/evaluation-history/me/{id}", ownHistory.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode ownData = objectMapper.readTree(ownResponse).path("data");
        assertThat(ownData.path("id").asLong()).isEqualTo(ownHistory.getId());
        assertThat(ownData.path("studentCode").asText()).isEqualTo(ownCode);

        mockMvc.perform(get("/api/evaluation-history/me/{id}", otherHistory.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private Student saveStudent(String studentCode, String fullName) {
        Student student = new Student();
        student.setStudentCode(studentCode);
        student.setFaceDataId(studentCode);
        student.setFullName(fullName);
        student.setClassName("7A1");
        return studentRepository.save(student);
    }

    private UserAccount saveUser(String email, String username, Role role, Student student) {
        UserAccount user = new UserAccount();
        user.setEmail(email);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode("123456"));
        user.setRole(role);
        user.setEnabled(true);
        user.setStudent(student);
        return userAccountRepository.save(user);
    }

    private EvaluationHistory saveHistory(Student student, UserAccount createdBy, String violationType) {
        EvaluationHistory history = new EvaluationHistory();
        history.setStudent(student);
        history.setStudentCodeSnapshot(student.getStudentCode());
        history.setStudentNameSnapshot(student.getFullName());
        history.setClassNameSnapshot(student.getClassName());
        history.setSelectedMethod(EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);
        history.setComplianceStatus(ComplianceStatus.NON_COMPLIANT);
        history.setViolationTypes(new LinkedHashSet<>(List.of(violationType)));
        history.setViolationSummary(violationType);
        history.setFinalScore(95);
        history.setDeductedPoints(5);
        history.setCreatedBy(createdBy);
        return evaluationHistoryRepository.save(history);
    }
}
