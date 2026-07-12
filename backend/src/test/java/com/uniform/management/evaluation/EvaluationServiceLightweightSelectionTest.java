package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uniform.management.common.BadRequestException;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.common.enums.Role;
import com.uniform.management.evaluation.dto.EvaluationCompareResponse;
import com.uniform.management.evaluationhistory.EvaluationHistoryRepository;
import com.uniform.management.image.EvaluationImage;
import com.uniform.management.image.ImageService;
import com.uniform.management.security.AppUserDetails;
import com.uniform.management.student.MoralityService;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.uniformai.UniformAiClient;
import com.uniform.management.uniformai.UniformAiException;
import com.uniform.management.uniformschedule.ScheduleComplianceResult;
import com.uniform.management.uniformschedule.UniformRequirementScheduleService;
import com.uniform.management.user.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceLightweightSelectionTest {

    @Mock
    private UniformAiClient uniformAiClient;
    @Mock
    private ImageService imageService;
    @Mock
    private AiProcessedImageImporter processedImageImporter;
    @Mock
    private StudentRepository studentRepository;
    @Mock
    private EvaluationRunRepository evaluationRunRepository;
    @Mock
    private EvaluationHistoryRepository evaluationHistoryRepository;
    @Mock
    private UniformRequirementScheduleService scheduleService;
    @Mock
    private MoralityService moralityService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ObjectMapper objectMapper;
    private EvaluationService evaluationService;
    private MockMultipartFile image;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        EvaluationResultExtractor extractor = new EvaluationResultExtractor();
        evaluationService = new EvaluationService(
                uniformAiClient,
                imageService,
                processedImageImporter,
                studentRepository,
                evaluationRunRepository,
                evaluationHistoryRepository,
                extractor,
                new UniformComplianceService(objectMapper),
                scheduleService,
                new UniformComplianceScoreService(),
                moralityService,
                objectMapper,
                transactionManager
        );
        image = new MockMultipartFile("image", "student.jpg", "image/jpeg", new byte[]{1, 2, 3});

        UserAccount admin = new UserAccount();
        admin.setEmail("lightweight-admin@test.local");
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        AppUserDetails details = new AppUserDetails(admin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())
        );
    }

    @AfterEach
    void tearDown() {
        evaluationService.shutdownComparisonExecutor();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @MethodSource("lightweightSelections")
    void runsOnlyTheSelectedLightweightMethod(
            String uiSelection,
            String canonicalAiMethod,
            EvaluationMethod expectedResponseMethod
    ) {
        JsonNode aiResponse = lightweightAiResponse(canonicalAiMethod);
        when(uniformAiClient.evaluateLightweight(same(image), isNull(), anyString(), anyString()))
                .thenReturn(aiResponse);
        when(imageService.savePreAiUpload(same(image), any())).thenReturn(new EvaluationImage());
        when(processedImageImporter.importProcessedImage(isNull(), any(EvaluationMethod.class), any(JsonNode.class)))
                .thenReturn(new EvaluationImage());
        when(scheduleService.evaluate(isNull(), any(JsonNode.class), any(Instant.class)))
                .thenReturn(unconfiguredSchedule());
        when(scheduleService.withScheduleResult(any(JsonNode.class), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(evaluationRunRepository.save(any(EvaluationRun.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EvaluationCompareResponse response = evaluationService.lightweight(image, null, uiSelection);

        verify(uniformAiClient, times(1)).evaluateLightweight(
                same(image), isNull(), org.mockito.ArgumentMatchers.eq("identify"),
                org.mockito.ArgumentMatchers.eq(canonicalAiMethod)
        );
        verify(uniformAiClient, never()).evaluateAdvanced(any(), any(), any(), any());
        verify(uniformAiClient, never()).evaluateStudent(any(), any(), any(), any());
        verify(uniformAiClient, never()).evaluateStudentCandidates(any(), any(), any());
        verify(uniformAiClient, never()).evaluateUniformCandidates(any());

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).method()).isEqualTo(expectedResponseMethod);
        assertThat(response.results().get(0).methodKey()).isEqualTo(canonicalAiMethod);
        assertThat(response.results().get(0).status()).isEqualTo("completed");
        if (expectedResponseMethod.isMethod1Slot()) {
            assertThat(response.method2().status()).isEqualTo("pending");
        } else {
            assertThat(response.method1().status()).isEqualTo("pending");
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingLightweightMethodWithoutCallingAi(String selectedMethod) {
        assertThatThrownBy(() -> evaluationService.lightweight(image, null, selectedMethod))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Vui lòng chọn phương pháp đánh giá nhanh")
                .hasMessageContaining("YOLOV8_V2")
                .hasMessageContaining("GROUNDING_DINO_V2");

        verifyNoInteractions(uniformAiClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "hybrid",
            "SCHP",
            "UNKNOWN_METHOD",
            "METHOD_1_GROUNDING_DINO_SCHP_FLORENCE",
            "METHOD_2_YOLOV8_SCHP_FLORENCE"
    })
    void rejectsUnsupportedLightweightMethodWithoutCallingAi(String selectedMethod) {
        assertThatThrownBy(() -> evaluationService.lightweight(image, null, selectedMethod))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Phương pháp đánh giá nhanh không hợp lệ")
                .hasMessageContaining("YOLOV8_V2")
                .hasMessageContaining("GROUNDING_DINO_V2");

        verifyNoInteractions(uniformAiClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"YOLOV8_V2", "GROUNDING_DINO_V2"})
    void propagatesAiFailureWithoutPersistingACompletedRun(String selectedMethod) {
        when(uniformAiClient.evaluateLightweight(same(image), isNull(), anyString(), anyString()))
                .thenThrow(new RuntimeException("mock AI outage"));

        assertThatThrownBy(() -> evaluationService.lightweight(image, null, selectedMethod))
                .isInstanceOf(UniformAiException.class)
                .hasMessageContaining("Không thể chạy đánh giá nhanh")
                .hasMessageContaining("Vui lòng kiểm tra dịch vụ AI")
                .hasCauseInstanceOf(RuntimeException.class);

        verifyNoInteractions(imageService, processedImageImporter, evaluationRunRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"YOLOV8_V2", "GROUNDING_DINO_V2"})
    void rejectsUnsuccessfulAiPayloadWithoutPersistingACompletedRun(String selectedMethod) {
        ObjectNode aiResponse = objectMapper.createObjectNode();
        aiResponse.put("success", false);
        aiResponse.put("message", "mock detector failure");
        when(uniformAiClient.evaluateLightweight(same(image), isNull(), anyString(), anyString()))
                .thenReturn(aiResponse);

        assertThatThrownBy(() -> evaluationService.lightweight(image, null, selectedMethod))
                .isInstanceOf(UniformAiException.class)
                .hasMessageContaining("Dịch vụ AI không thể hoàn tất đánh giá nhanh");

        verifyNoInteractions(imageService, processedImageImporter, evaluationRunRepository);
    }

    private static Stream<Arguments> lightweightSelections() {
        return Stream.of(
                Arguments.of(
                        "YOLOV8_V2",
                        EvaluationMethod.METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM.getCandidateKey(),
                        EvaluationMethod.METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM
                ),
                Arguments.of(
                        "GROUNDING_DINO_V2",
                        EvaluationMethod.METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO.getCandidateKey(),
                        EvaluationMethod.METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO
                )
        );
    }

    private JsonNode lightweightAiResponse(String methodKey) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("success", true);
        ObjectNode uniform = response.putObject("data").putObject("uniform");
        uniform.put("evaluation_id", "lightweight-test");
        uniform.putArray("candidate_methods").add(methodKey);
        ObjectNode candidate = uniform.putArray("candidates").addObject();
        candidate.put("method", methodKey);
        candidate.put("available", true);
        ObjectNode result = candidate.putObject("result");
        result.putArray("accepted_components");
        result.putArray("missing_components");
        result.putArray("rejected_components");
        result.putObject("final_summary").put("is_compliant", true).put("score", 100);
        return response;
    }

    private ScheduleComplianceResult unconfiguredSchedule() {
        return new ScheduleComplianceResult(
                false,
                false,
                "SCHEDULE_NOT_CONFIGURED",
                null,
                null,
                null,
                "Asia/Ho_Chi_Minh",
                Instant.parse("2026-07-11T00:00:00Z"),
                List.of(),
                List.of(),
                List.of(),
                0,
                null,
                0,
                ComplianceStatus.NEEDS_REVIEW,
                objectMapper.createObjectNode()
        );
    }
}
