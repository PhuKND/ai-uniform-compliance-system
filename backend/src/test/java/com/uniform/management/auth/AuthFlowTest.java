package com.uniform.management.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StudentRepository studentRepository;

    @Test
    void studentCanRegisterLoginAndCannotAccessAdminFaceData() throws Exception {
        String dateOfBirth = LocalDate.now().minusYears(13).toString();
        String registerJson = """
                {
                  "fullName": "Nguyễn Văn An",
                  "gender": "MALE",
                  "dateOfBirth": "%s",
                  "className": "7A1",
                  "schoolYear": "2026-2027",
                  "phone": "0900000000",
                  "email": "an@example.com",
                  "address": "Hà Nội",
                  "password": "secret123"
                }
                """.formatted(dateOfBirth);

        String registerBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode register = objectMapper.readTree(registerBody);
        assertThat(register.path("data").path("student").path("studentCode").asText()).isEqualTo("AN001");
        assertThat(register.path("data").path("student").path("dateOfBirth").asText()).isEqualTo(dateOfBirth);
        assertThat(register.path("data").path("student").path("className").asText()).isEqualTo("7A1");
        assertThat(register.path("data").path("student").path("age").asInt()).isEqualTo(13);
        String token = register.path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();

        String loginJson = """
                {"email": "an@example.com", "password": "secret123"}
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/face-data")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateStudentAccountAndStudentCanLoginWithUsername() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String studentCode = "ACC" + suffix.substring(Math.max(0, suffix.length() - 8));

        Student student = new Student();
        student.setStudentCode(studentCode);
        student.setFaceDataId(studentCode);
        student.setFullName("Nguyen Van Account");
        student.setClassName("10A1");
        studentRepository.save(student);

        String username = "student" + suffix.substring(Math.max(0, suffix.length() - 8));
        String email = username + "@example.com";
        String accountJson = """
                {
                  "username": "%s",
                  "email": "%s",
                  "password": "123456",
                  "confirmPassword": "123456"
                }
                """.formatted(username, email);

        String adminLoginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@test.local", "password": "Admin@123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String adminToken = objectMapper.readTree(adminLoginBody).path("data").path("accessToken").asText();

        String accountBody = mockMvc.perform(post("/api/students/{studentCode}/account", studentCode)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode account = objectMapper.readTree(accountBody).path("data");
        assertThat(account.path("username").asText()).isEqualTo(username);
        assertThat(account.path("role").asText()).isEqualTo("STUDENT");
        assertThat(account.path("student").path("hasAccount").asBoolean()).isTrue();

        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "123456"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode login = objectMapper.readTree(loginBody).path("data");
        assertThat(login.path("role").asText()).isEqualTo("STUDENT");
        assertThat(login.path("student").path("studentCode").asText()).isEqualTo(studentCode);
        String token = login.path("accessToken").asText();

        mockMvc.perform(get("/api/students/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/students")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/students/{studentCode}/account", studentCode)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson))
                .andExpect(status().isBadRequest());
    }
}
