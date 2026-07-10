package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.common.enums.Role;
import com.uniform.management.evaluation.dto.ChooseOfficialRequest;
import com.uniform.management.evaluationhistory.EvaluationHistory;
import com.uniform.management.evaluationhistory.EvaluationHistoryRepository;
import com.uniform.management.image.ImageService;
import com.uniform.management.security.AppUserDetails;
import com.uniform.management.student.MoralityService;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.uniformai.UniformAiClient;
import com.uniform.management.uniformschedule.ScheduleComplianceResult;
import com.uniform.management.uniformschedule.UniformRequirementScheduleService;
import com.uniform.management.user.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationManualDeductionOverrideTest {

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
    private EvaluationResultExtractor extractor;
    @Mock
    private UniformComplianceService complianceService;
    @Mock
    private UniformRequirementScheduleService scheduleService;
    @Mock
    private UniformComplianceScoreService scoreService;
    @Mock
    private MoralityService moralityService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ObjectMapper objectMapper;
    private EvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        evaluationService = new EvaluationService(
                uniformAiClient,
                imageService,
                processedImageImporter,
                studentRepository,
                evaluationRunRepository,
                evaluationHistoryRepository,
                extractor,
                complianceService,
                scheduleService,
                scoreService,
                moralityService,
                objectMapper,
                transactionManager
        );
    }

    @AfterEach
    void tearDown() {
        evaluationService.shutdownComparisonExecutor();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 6, 7, 10})
    void officialSavePersistsAdminEditedDeduction(int editedDeduction) {
        UserAccount admin = new UserAccount();
        admin.setEmail("admin-override@test.local");
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AppUserDetails(admin),
                        null,
                        new AppUserDetails(admin).getAuthorities()
                )
        );

        Student student = new Student();
        student.setStudentCode("OVERRIDE01");
        student.setFaceDataId("OVERRIDE01");
        student.setFullName("Học sinh kiểm thử");
        student.setClassName("7A1");

        EvaluationRun run = new EvaluationRun();
        run.setRequestedStudent(student);
        run.setRecognizedStudent(student);
        run.setRecognizedStudentCode(student.getStudentCode());
        run.setMethod2Status(EvaluationProcessingStatus.COMPLETED);
        run.setRawMethod2Json("{}");
        run.setCreatedBy(admin);

        JsonNode candidate = objectMapper.createObjectNode();
        JsonNode emptyArray = objectMapper.createArrayNode();
        UniformComplianceService.UniformComplianceDecision decision =
                new UniformComplianceService.UniformComplianceDecision(
                        ComplianceStatus.NON_COMPLIANT,
                        false,
                        Set.of(),
                        emptyArray,
                        emptyArray,
                        emptyArray,
                        objectMapper.createObjectNode(),
                        objectMapper.createObjectNode(),
                        Set.of("MISSING_RED_SCARF"),
                        "Thiếu khăn quàng đỏ",
                        "Cần bổ sung khăn quàng đỏ",
                        60,
                        true,
                        false,
                        false,
                        false,
                        false,
                        "SCHEDULE"
                );
        ScheduleComplianceResult scheduleResult = new ScheduleComplianceResult(
                true,
                true,
                null,
                "7A1",
                DayOfWeek.MONDAY,
                "Thứ Hai",
                "Asia/Ho_Chi_Minh",
                Instant.now(),
                List.of("khan_quang_do"),
                List.of(),
                List.of("khan_quang_do"),
                1,
                60,
                20,
                ComplianceStatus.NON_COMPLIANT,
                objectMapper.createObjectNode()
        );
        UniformComplianceScoreService.ScoreDecision scoreDecision =
                new UniformComplianceScoreService.ScoreDecision(
                        60,
                        ComplianceStatus.NON_COMPLIANT,
                        20,
                        false,
                        Set.of(),
                        "Không đạt lịch đồng phục"
                );

        when(evaluationRunRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(run));
        when(extractor.candidateComplianceStatus(any(JsonNode.class))).thenReturn(ComplianceStatus.NON_COMPLIANT);
        when(complianceService.evaluate(eq(student), any(JsonNode.class))).thenReturn(decision);
        when(complianceService.withBackendDecision(any(JsonNode.class), eq(student), eq(decision)))
                .thenReturn(candidate);
        when(scheduleService.evaluate(eq(student), any(JsonNode.class), any(Instant.class)))
                .thenReturn(scheduleResult);
        when(scoreService.decide(scheduleResult, decision, ComplianceStatus.NON_COMPLIANT))
                .thenReturn(scoreDecision);
        when(scheduleService.withScheduleResult(any(JsonNode.class), eq(scheduleResult), eq(scoreDecision)))
                .thenReturn(candidate);
        when(scheduleService.toJson(scheduleResult)).thenReturn(objectMapper.createObjectNode());
        when(evaluationHistoryRepository.save(any(EvaluationHistory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        evaluationService.chooseOfficial(
                42L,
                new ChooseOfficialRequest(
                        EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE.name(),
                        null,
                        editedDeduction,
                        "Giá trị đã được quản trị viên kiểm tra"
                )
        );

        ArgumentCaptor<EvaluationHistory> historyCaptor = ArgumentCaptor.forClass(EvaluationHistory.class);
        verify(evaluationHistoryRepository).save(historyCaptor.capture());
        EvaluationHistory saved = historyCaptor.getValue();
        assertThat(saved.getDeductedPoints()).isEqualTo(editedDeduction);
        assertThat(saved.getScheduleDeductedPoints()).isEqualTo(20);
        assertThat(run.isOfficialSaved()).isTrue();
        verify(moralityService).deduct(
                eq(student),
                eq(editedDeduction),
                any(String.class),
                eq(saved),
                eq(admin)
        );
    }
}
