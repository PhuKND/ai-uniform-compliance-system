package com.uniform.management.correctionrequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.common.enums.Role;
import com.uniform.management.evaluationhistory.EvaluationHistory;
import com.uniform.management.evaluationhistory.EvaluationHistoryRepository;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.user.UserAccount;
import com.uniform.management.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorrectionRequestControllerTest {

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
    private CorrectionRequestRepository correctionRequestRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void studentCanSubmitOnlyForOwnHistoryAndDuplicatePendingIsRejected() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(multipart("/api/correction-requests")
                        .header("Authorization", bearer(fixture.studentToken()))
                        .param("evaluationHistoryId", fixture.otherHistory().getId().toString())
                        .param("requestedDeduction", "10")
                        .param("reason", "Đánh giá này không thuộc về tôi"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/evaluations/{runId}/choose-official", 999999L)
                        .header("Authorization", bearer(fixture.studentToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"selectedMethod":"YOLOV8_V2","deductedPoints":0}
                                """))
                .andExpect(status().isForbidden());

        String createdBody = mockMvc.perform(multipart("/api/correction-requests")
                        .header("Authorization", bearer(fixture.studentToken()))
                        .param("evaluationHistoryId", fixture.ownHistory().getId().toString())
                        .param("requestedDeduction", "10")
                        .param("reason", "Đề nghị kiểm tra lại ảnh")
                        .param("evidenceNote", "Khăn quàng bị che một phần"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode created = objectMapper.readTree(createdBody).path("data");
        assertThat(created.path("evaluationHistoryId").asLong()).isEqualTo(fixture.ownHistory().getId());
        assertThat(created.path("deductionAtSubmission").asInt()).isEqualTo(20);
        assertThat(created.path("requestedDeduction").asInt()).isEqualTo(10);
        assertThat(created.path("status").asText()).isEqualTo("PENDING");

        mockMvc.perform(multipart("/api/correction-requests")
                        .header("Authorization", bearer(fixture.studentToken()))
                        .param("evaluationHistoryId", fixture.ownHistory().getId().toString())
                        .param("requestedDeduction", "5")
                        .param("reason", "Gửi trùng khi yêu cầu trước còn chờ"))
                .andExpect(status().isBadRequest());

        String myRequests = mockMvc.perform(get("/api/correction-requests/me")
                        .header("Authorization", bearer(fixture.studentToken())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode content = objectMapper.readTree(myRequests).path("data").path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("studentCode").asText()).isEqualTo(fixture.student().getStudentCode());
    }

    @Test
    void adminApprovalSetsRequestedTotalExactlyOnce() throws Exception {
        Fixture fixture = fixture();
        long requestId = createRequest(fixture, 10);

        String responseBody = mockMvc.perform(post("/api/correction-requests/{id}/approve", requestId)
                        .header("Authorization", bearer(fixture.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adminResponseNote":"Đã kiểm tra minh chứng","updatedViolationSummary":"Đã điều chỉnh theo minh chứng"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode approved = objectMapper.readTree(responseBody).path("data");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("deductionAfterDecision").asInt()).isEqualTo(10);
        assertThat(approved.path("resolvedBy").asText()).isEqualTo(fixture.admin().getEmail());
        assertThat(approved.path("resolvedAt").asText()).isNotBlank();

        EvaluationHistory updatedHistory = evaluationHistoryRepository.findById(fixture.ownHistory().getId()).orElseThrow();
        Student updatedStudent = studentRepository.findById(fixture.student().getId()).orElseThrow();
        assertThat(updatedHistory.getDeductedPoints()).isEqualTo(10);
        assertThat(updatedHistory.getViolationSummary()).isEqualTo("Đã điều chỉnh theo minh chứng");
        assertThat(updatedStudent.getMoralityScore()).isEqualTo(90);

        mockMvc.perform(post("/api/correction-requests/{id}/approve", requestId)
                        .header("Authorization", bearer(fixture.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(evaluationHistoryRepository.findById(fixture.ownHistory().getId()).orElseThrow().getDeductedPoints())
                .isEqualTo(10);
        assertThat(studentRepository.findById(fixture.student().getId()).orElseThrow().getMoralityScore())
                .isEqualTo(90);
    }

    @Test
    void adminRejectionLeavesDeductionUnchangedAndCannotBeReprocessed() throws Exception {
        Fixture fixture = fixture();
        long requestId = createRequest(fixture, 5);

        mockMvc.perform(post("/api/correction-requests/{id}/reject", requestId)
                        .header("Authorization", bearer(fixture.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adminResponseNote":"Minh chứng chưa đủ rõ"}
                                """))
                .andExpect(status().isOk());

        CorrectionRequest rejected = correctionRequestRepository.findById(requestId).orElseThrow();
        assertThat(rejected.getStatus().name()).isEqualTo("REJECTED");
        assertThat(rejected.getDeductionAfterDecision()).isEqualTo(20);
        assertThat(evaluationHistoryRepository.findById(fixture.ownHistory().getId()).orElseThrow().getDeductedPoints())
                .isEqualTo(20);
        assertThat(studentRepository.findById(fixture.student().getId()).orElseThrow().getMoralityScore())
                .isEqualTo(80);

        mockMvc.perform(post("/api/correction-requests/{id}/approve", requestId)
                        .header("Authorization", bearer(fixture.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(evaluationHistoryRepository.findById(fixture.ownHistory().getId()).orElseThrow().getDeductedPoints())
                .isEqualTo(20);
    }

    private long createRequest(Fixture fixture, int requestedDeduction) throws Exception {
        String body = mockMvc.perform(multipart("/api/correction-requests")
                        .header("Authorization", bearer(fixture.studentToken()))
                        .param("evaluationHistoryId", fixture.ownHistory().getId().toString())
                        .param("requestedDeduction", Integer.toString(requestedDeduction))
                        .param("reason", "Đề nghị điều chỉnh điểm trừ"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    private Fixture fixture() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String shortSuffix = suffix.substring(Math.max(0, suffix.length() - 8));

        Student student = saveStudent("CR" + shortSuffix, "Nguyễn Văn Yêu Cầu", 80);
        Student otherStudent = saveStudent("OT" + shortSuffix, "Trần Văn Khác", 85);
        UserAccount admin = saveUser("correction-admin-" + suffix + "@test.local", Role.ADMIN, null);
        UserAccount studentUser = saveUser("correction-student-" + suffix + "@test.local", Role.STUDENT, student);

        EvaluationHistory ownHistory = saveHistory(student, admin, 20);
        EvaluationHistory otherHistory = saveHistory(otherStudent, admin, 15);
        return new Fixture(
                admin,
                student,
                ownHistory,
                otherHistory,
                login(admin.getEmail()),
                login(studentUser.getEmail())
        );
    }

    private Student saveStudent(String code, String name, int moralityScore) {
        Student student = new Student();
        student.setStudentCode(code);
        student.setFaceDataId(code);
        student.setFullName(name);
        student.setClassName("7A1");
        student.setMoralityScore(moralityScore);
        return studentRepository.save(student);
    }

    private UserAccount saveUser(String email, Role role, Student student) {
        UserAccount user = new UserAccount();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("test-password"));
        user.setRole(role);
        user.setEnabled(true);
        user.setStudent(student);
        return userAccountRepository.save(user);
    }

    private EvaluationHistory saveHistory(Student student, UserAccount admin, int deductedPoints) {
        EvaluationHistory history = new EvaluationHistory();
        history.setStudent(student);
        history.setStudentCodeSnapshot(student.getStudentCode());
        history.setStudentNameSnapshot(student.getFullName());
        history.setClassNameSnapshot(student.getClassName());
        history.setSelectedMethod(EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);
        history.setComplianceStatus(ComplianceStatus.NON_COMPLIANT);
        history.setViolationTypes(new LinkedHashSet<>(List.of("MISSING_RED_SCARF")));
        history.setViolationSummary("Thiếu khăn quàng đỏ");
        history.setFinalScore(60);
        history.setDeductedPoints(deductedPoints);
        history.setCreatedBy(admin);
        return evaluationHistoryRepository.save(history);
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"test-password"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data").path("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Fixture(
            UserAccount admin,
            Student student,
            EvaluationHistory ownHistory,
            EvaluationHistory otherHistory,
            String adminToken,
            String studentToken
    ) {
    }
}
