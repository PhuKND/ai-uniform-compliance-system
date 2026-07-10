package com.uniform.management.uniformschedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniform.management.common.enums.Role;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleDayRequest;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleUpdateRequest;
import com.uniform.management.user.UserAccount;
import com.uniform.management.user.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.DayOfWeek;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentUniformScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UniformRequirementScheduleService scheduleService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void studentFetchesOnlyOwnClassSevenDaySchedule() throws Exception {
        String suffix = suffix();
        Student ownStudent = saveStudent("SCH" + suffix, "Schedule Student", "6A-" + suffix);
        saveStudent("OTH" + suffix, "Other Student", "9B-" + suffix);
        String studentEmail = "student-schedule-" + suffix + "@test.local";
        saveUser(studentEmail, Role.STUDENT, ownStudent);

        scheduleService.updateWeeklySchedule(ownStudent.getClassName(), weekly(Map.of(
                DayOfWeek.MONDAY, List.of(UniformComponent.AO_SO_MI_TRANG.key(), UniformComponent.KHAN_QUANG_DO.key())
        )));

        String token = login(studentEmail, "123456");
        String response = mockMvc.perform(get("/api/student/uniform-schedule")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("studentId").asText()).isEqualTo(ownStudent.getStudentCode());
        assertThat(data.path("className").asText()).isEqualTo(ownStudent.getClassName());
        assertThat(data.path("days")).hasSize(7);
        JsonNode monday = data.path("days").get(0);
        assertThat(monday.path("dayOfWeek").asText()).isEqualTo("MONDAY");
        assertThat(monday.path("displayName").asText()).isEqualTo("Thứ Hai");
        assertThat(monday.path("hasSchedule").asBoolean()).isTrue();
        assertThat(monday.path("requiredComponents")).hasSize(2);
        assertThat(response).doesNotContain("9B-" + suffix);

        mockMvc.perform(get("/api/admin/uniform-requirement-schedules/{classId}", ownStudent.getClassName())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        MockMultipartFile image = new MockMultipartFile("image", "uniform.jpg", "image/jpeg", new byte[] {1, 2, 3});
        mockMvc.perform(multipart("/api/evaluations/compare")
                        .file(image)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRetainsAccessToAdminScheduleEndpoint() throws Exception {
        String suffix = suffix();
        Student student = saveStudent("ADM" + suffix, "Admin Class Student", "7A-" + suffix);
        String adminToken = login("admin@test.local", "Admin@123456");

        String response = mockMvc.perform(get("/api/admin/uniform-requirement-schedules/{classId}", student.getClassName())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(response).path("data").path("className").asText()).isEqualTo(student.getClassName());
    }

    private Student saveStudent(String studentCode, String fullName, String className) {
        Student student = new Student();
        student.setStudentCode(studentCode);
        student.setFaceDataId(studentCode);
        student.setFullName(fullName);
        student.setClassName(className);
        return studentRepository.save(student);
    }

    private void saveUser(String email, Role role, Student student) {
        UserAccount user = new UserAccount();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("123456"));
        user.setRole(role);
        user.setEnabled(true);
        user.setStudent(student);
        userAccountRepository.save(user);
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data").path("accessToken").asText();
    }

    private UniformRequirementScheduleUpdateRequest weekly(Map<DayOfWeek, List<String>> overrides) {
        Map<DayOfWeek, List<String>> values = new EnumMap<>(DayOfWeek.class);
        values.putAll(overrides);
        return new UniformRequirementScheduleUpdateRequest(List.of(
                new UniformRequirementScheduleDayRequest(DayOfWeek.MONDAY, values.getOrDefault(DayOfWeek.MONDAY, List.of())),
                new UniformRequirementScheduleDayRequest(DayOfWeek.TUESDAY, values.getOrDefault(DayOfWeek.TUESDAY, List.of())),
                new UniformRequirementScheduleDayRequest(DayOfWeek.WEDNESDAY, values.getOrDefault(DayOfWeek.WEDNESDAY, List.of())),
                new UniformRequirementScheduleDayRequest(DayOfWeek.THURSDAY, values.getOrDefault(DayOfWeek.THURSDAY, List.of())),
                new UniformRequirementScheduleDayRequest(DayOfWeek.FRIDAY, values.getOrDefault(DayOfWeek.FRIDAY, List.of())),
                new UniformRequirementScheduleDayRequest(DayOfWeek.SATURDAY, values.getOrDefault(DayOfWeek.SATURDAY, List.of())),
                new UniformRequirementScheduleDayRequest(DayOfWeek.SUNDAY, values.getOrDefault(DayOfWeek.SUNDAY, List.of()))
        ));
    }

    private String suffix() {
        String value = Long.toString(System.nanoTime());
        return value.substring(Math.max(0, value.length() - 8));
    }
}
