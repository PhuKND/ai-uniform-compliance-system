package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uniform.management.common.BadRequestException;
import com.uniform.management.common.ResourceNotFoundException;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.common.enums.ImageType;
import com.uniform.management.evaluation.dto.ChooseOfficialRequest;
import com.uniform.management.evaluation.dto.EvaluationCompareResponse;
import com.uniform.management.evaluation.dto.MethodResultResponse;
import com.uniform.management.evaluationhistory.EvaluationHistory;
import com.uniform.management.evaluationhistory.EvaluationHistoryRepository;
import com.uniform.management.evaluationhistory.dto.EvaluationHistoryResponse;
import com.uniform.management.image.EvaluationImage;
import com.uniform.management.image.ImageService;
import com.uniform.management.security.SecurityUtils;
import com.uniform.management.student.MoralityService;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.student.dto.StudentResponse;
import com.uniform.management.uniformschedule.ScheduleComplianceResult;
import com.uniform.management.uniformschedule.UniformRequirementScheduleService;
import com.uniform.management.uniformai.UniformAiClient;
import com.uniform.management.user.UserAccount;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final UniformAiClient uniformAiClient;
    private final ImageService imageService;
    private final AiProcessedImageImporter processedImageImporter;
    private final StudentRepository studentRepository;
    private final EvaluationRunRepository evaluationRunRepository;
    private final EvaluationHistoryRepository evaluationHistoryRepository;
    private final EvaluationResultExtractor extractor;
    private final UniformComplianceService complianceService;
    private final UniformRequirementScheduleService scheduleService;
    private final UniformComplianceScoreService scoreService;
    private final MoralityService moralityService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService comparisonExecutor = Executors.newFixedThreadPool(4);

    public EvaluationService(
            UniformAiClient uniformAiClient,
            ImageService imageService,
            AiProcessedImageImporter processedImageImporter,
            StudentRepository studentRepository,
            EvaluationRunRepository evaluationRunRepository,
            EvaluationHistoryRepository evaluationHistoryRepository,
            EvaluationResultExtractor extractor,
            UniformComplianceService complianceService,
            UniformRequirementScheduleService scheduleService,
            UniformComplianceScoreService scoreService,
            MoralityService moralityService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.uniformAiClient = uniformAiClient;
        this.imageService = imageService;
        this.processedImageImporter = processedImageImporter;
        this.studentRepository = studentRepository;
        this.evaluationRunRepository = evaluationRunRepository;
        this.evaluationHistoryRepository = evaluationHistoryRepository;
        this.extractor = extractor;
        this.complianceService = complianceService;
        this.scheduleService = scheduleService;
        this.scoreService = scoreService;
        this.moralityService = moralityService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PreDestroy
    public void shutdownComparisonExecutor() {
        comparisonExecutor.shutdown();
    }

    @Transactional
    public EvaluationCompareResponse compare(MultipartFile image, String studentCode) {
        UserAccount admin = SecurityUtils.currentUser();
        Student requestedStudent = null;
        if (studentCode != null && !studentCode.isBlank()) {
            requestedStudent = studentRepository.findByStudentCode(studentCode)
                    .orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y h\u1ecdc sinh: " + studentCode));
        }

        String faceMode = requestedStudent == null ? "identify" : "verify";
        JsonNode aiResponse = runIntegratedEvaluation(image, requestedStudent, faceMode);
        JsonNode method1 = candidateOrError(aiResponse, EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE);
        JsonNode method2 = candidateOrError(aiResponse, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);
        EvaluationImage original = savePreAiImage(aiResponse, image);

        String recognizedCode = extractor.recognizedStudentCode(aiResponse);
        Student recognizedStudent = recognizedCode == null
                ? null
                : studentRepository.findByStudentCode(recognizedCode).orElse(null);
        Student responseStudent = recognizedStudent != null ? recognizedStudent : requestedStudent;
        Instant completedAt = Instant.now();
        PreparedCandidate method1Prepared = prepareCandidate(responseStudent, method1, completedAt);
        PreparedCandidate method2Prepared = prepareCandidate(responseStudent, method2, completedAt);

        ImageImportOutcome method1Import = importProcessedImage(null, EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE, method1Prepared.candidate());
        ImageImportOutcome method2Import = importProcessedImage(null, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE, method2Prepared.candidate());
        EvaluationImage method1Image = method1Import.image();
        EvaluationImage method2Image = method2Import.image();

        EvaluationRun run = new EvaluationRun();
        run.setRequestedStudent(requestedStudent);
        run.setRequestedStudentCode(requestedStudent == null ? null : requestedStudent.getStudentCode());
        run.setRecognizedStudent(recognizedStudent);
        run.setRecognizedStudentCode(recognizedCode);
        run.setUniformAiEvaluationId(extractor.uniformAiEvaluationId(aiResponse));
        run.setPreAiImagePath(extractor.preAiImagePath(aiResponse));
        run.setPreAiImageUrl(uniformAiClient.resolveImageUrl(extractor.preAiImageUrl(aiResponse)));
        run.setOriginalImage(original);
        run.setMethod1Image(method1Image);
        run.setMethod2Image(method2Image);
        run.setMethod1Compliance(method1Prepared.complianceStatus());
        run.setMethod2Compliance(method2Prepared.complianceStatus());
        run.setMethod1ProcessedImagePath(extractor.processedImagePath(method1Prepared.candidate()));
        run.setMethod1ProcessedImageUrl(managedImageUrl(method1Image));
        run.setMethod2ProcessedImagePath(extractor.processedImagePath(method2Prepared.candidate()));
        run.setMethod2ProcessedImageUrl(managedImageUrl(method2Image));
        run.setRawMethod1Json(toJson(method1Prepared.candidate()));
        run.setMethod1ScheduleSnapshotJson(scheduleSnapshotJson(method1Prepared.candidate()));
        run.setRawMethod2Json(toJson(method2Prepared.candidate()));
        run.setMethod2ScheduleSnapshotJson(scheduleSnapshotJson(method2Prepared.candidate()));
        run.setRawAiResponseJson(toJson(aiResponse));
        run.setMethod1Status(EvaluationProcessingStatus.COMPLETED);
        run.setMethod1Error(method1Import.error());
        run.setMethod1CompletedAt(completedAt);
        run.setMethod2Status(EvaluationProcessingStatus.COMPLETED);
        run.setMethod2Error(method2Import.error());
        run.setMethod2CompletedAt(completedAt);
        run.setCreatedBy(admin);
        evaluationRunRepository.save(run);

        return toCompareResponse(run, responseStudent);
    }

    @Transactional
    public EvaluationCompareResponse advanced(MultipartFile image, String studentCode, EvaluationMethod method) {
        UserAccount admin = SecurityUtils.currentUser();
        Student requestedStudent = resolveRequestedStudent(studentCode);
        String faceMode = requestedStudent == null ? "identify" : "verify";
        JsonNode aiResponse = runAdvancedEvaluation(image, requestedStudent, faceMode, method);
        JsonNode candidate = candidateOrError(aiResponse, method);
        EvaluationImage original = savePreAiImage(aiResponse, image);

        String recognizedCode = extractor.recognizedStudentCode(aiResponse);
        Student recognizedStudent = recognizedCode == null
                ? null
                : studentRepository.findByStudentCode(recognizedCode).orElse(null);
        Student responseStudent = recognizedStudent != null ? recognizedStudent : requestedStudent;

        Instant completedAt = Instant.now();
        PreparedCandidate prepared = prepareCandidate(responseStudent, candidate, completedAt);
        ImageImportOutcome imageImport = importProcessedImage(null, method, prepared.candidate());

        EvaluationRun run = new EvaluationRun();
        run.setRequestedStudent(requestedStudent);
        run.setRequestedStudentCode(requestedStudent == null ? null : requestedStudent.getStudentCode());
        run.setRecognizedStudent(recognizedStudent);
        run.setRecognizedStudentCode(recognizedCode);
        run.setUniformAiEvaluationId(extractor.uniformAiEvaluationId(aiResponse));
        run.setPreAiImagePath(extractor.preAiImagePath(aiResponse));
        run.setPreAiImageUrl(uniformAiClient.resolveImageUrl(extractor.preAiImageUrl(aiResponse)));
        run.setOriginalImage(original);
        setMethodSuccess(run, method, prepared.candidate(), prepared.complianceStatus(), completedAt);
        setMethodImage(run, method, imageImport.image());
        setMethodImageImportError(run, method, imageImport.error());
        if (imageImport.image() != null) {
            setMethodProcessedImageUrl(run, method, managedImageUrl(imageImport.image()));
        }
        run.setRawAiResponseJson(toJson(aiResponse));
        run.setCreatedBy(admin);
        evaluationRunRepository.save(run);

        return toCompareResponse(run, responseStudent);
    }

    @Transactional
    public EvaluationCompareResponse lightweight(MultipartFile image, String studentCode) {
        return lightweight(image, studentCode, null);
    }

    @Transactional
    public EvaluationCompareResponse lightweight(MultipartFile image, String studentCode, String selectedMethod) {
        if (selectedMethod == null || selectedMethod.isBlank()) {
            return lightweightCompare(image, studentCode);
        }

        UserAccount admin = SecurityUtils.currentUser();
        Student requestedStudent = resolveRequestedStudent(studentCode);
        String faceMode = requestedStudent == null ? "identify" : "verify";
        EvaluationMethod method = lightweightMethodFromSelection(selectedMethod);
        JsonNode aiResponse = runLightweightEvaluation(image, requestedStudent, faceMode, method);
        JsonNode candidate = candidateOrError(aiResponse, method);
        EvaluationImage original = savePreAiImage(aiResponse, image);

        String recognizedCode = extractor.recognizedStudentCode(aiResponse);
        Student recognizedStudent = recognizedCode == null
                ? null
                : studentRepository.findByStudentCode(recognizedCode).orElse(null);
        Student responseStudent = recognizedStudent != null ? recognizedStudent : requestedStudent;
        Instant completedAt = Instant.now();
        PreparedCandidate prepared = prepareCandidate(responseStudent, candidate, completedAt);
        ImageImportOutcome imageImport = importProcessedImage(null, method, prepared.candidate());

        EvaluationRun run = new EvaluationRun();
        run.setRequestedStudent(requestedStudent);
        run.setRequestedStudentCode(requestedStudent == null ? null : requestedStudent.getStudentCode());
        run.setRecognizedStudent(recognizedStudent);
        run.setRecognizedStudentCode(recognizedCode);
        run.setUniformAiEvaluationId(extractor.uniformAiEvaluationId(aiResponse));
        run.setPreAiImagePath(extractor.preAiImagePath(aiResponse));
        run.setPreAiImageUrl(uniformAiClient.resolveImageUrl(extractor.preAiImageUrl(aiResponse)));
        run.setOriginalImage(original);
        setMethodSuccess(run, method, prepared.candidate(), prepared.complianceStatus(), completedAt);
        setMethodImage(run, method, imageImport.image());
        setMethodImageImportError(run, method, imageImport.error());
        if (imageImport.image() != null) {
            setMethodProcessedImageUrl(run, method, managedImageUrl(imageImport.image()));
        }
        run.setRawAiResponseJson(toJson(aiResponse));
        run.setCreatedBy(admin);
        evaluationRunRepository.save(run);

        return toCompareResponse(run, responseStudent);
    }

    private EvaluationCompareResponse lightweightCompare(MultipartFile image, String studentCode) {
        UserAccount admin = SecurityUtils.currentUser();
        Student requestedStudent = resolveRequestedStudent(studentCode);
        String faceMode = requestedStudent == null ? "identify" : "verify";
        JsonNode aiResponse = runLightweightEvaluation(image, requestedStudent, faceMode);
        EvaluationMethod method1Type = EvaluationMethod.METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO;
        EvaluationMethod method2Type = EvaluationMethod.METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM;
        JsonNode method1 = candidateOrError(aiResponse, method1Type);
        JsonNode method2 = candidateOrError(aiResponse, method2Type);
        EvaluationImage original = savePreAiImage(aiResponse, image);

        String recognizedCode = extractor.recognizedStudentCode(aiResponse);
        Student recognizedStudent = recognizedCode == null
                ? null
                : studentRepository.findByStudentCode(recognizedCode).orElse(null);
        Student responseStudent = recognizedStudent != null ? recognizedStudent : requestedStudent;
        Instant completedAt = Instant.now();
        PreparedCandidate method1Prepared = prepareCandidate(responseStudent, method1, completedAt);
        PreparedCandidate method2Prepared = prepareCandidate(responseStudent, method2, completedAt);

        ImageImportOutcome method1Import = importProcessedImage(null, method1Type, method1Prepared.candidate());
        ImageImportOutcome method2Import = importProcessedImage(null, method2Type, method2Prepared.candidate());
        EvaluationImage method1Image = method1Import.image();
        EvaluationImage method2Image = method2Import.image();

        EvaluationRun run = new EvaluationRun();
        run.setRequestedStudent(requestedStudent);
        run.setRequestedStudentCode(requestedStudent == null ? null : requestedStudent.getStudentCode());
        run.setRecognizedStudent(recognizedStudent);
        run.setRecognizedStudentCode(recognizedCode);
        run.setUniformAiEvaluationId(extractor.uniformAiEvaluationId(aiResponse));
        run.setPreAiImagePath(extractor.preAiImagePath(aiResponse));
        run.setPreAiImageUrl(uniformAiClient.resolveImageUrl(extractor.preAiImageUrl(aiResponse)));
        run.setOriginalImage(original);
        run.setMethod1Image(method1Image);
        run.setMethod2Image(method2Image);
        run.setMethod1Compliance(method1Prepared.complianceStatus());
        run.setMethod2Compliance(method2Prepared.complianceStatus());
        run.setMethod1ProcessedImagePath(extractor.processedImagePath(method1Prepared.candidate()));
        run.setMethod1ProcessedImageUrl(managedImageUrl(method1Image));
        run.setMethod2ProcessedImagePath(extractor.processedImagePath(method2Prepared.candidate()));
        run.setMethod2ProcessedImageUrl(managedImageUrl(method2Image));
        run.setRawMethod1Json(toJson(method1Prepared.candidate()));
        run.setMethod1ScheduleSnapshotJson(scheduleSnapshotJson(method1Prepared.candidate()));
        run.setRawMethod2Json(toJson(method2Prepared.candidate()));
        run.setMethod2ScheduleSnapshotJson(scheduleSnapshotJson(method2Prepared.candidate()));
        run.setRawAiResponseJson(toJson(aiResponse));
        run.setMethod1Status(EvaluationProcessingStatus.COMPLETED);
        run.setMethod1Error(method1Import.error());
        run.setMethod1CompletedAt(completedAt);
        run.setMethod2Status(EvaluationProcessingStatus.COMPLETED);
        run.setMethod2Error(method2Import.error());
        run.setMethod2CompletedAt(completedAt);
        run.setCreatedBy(admin);
        evaluationRunRepository.save(run);

        return toCompareResponse(run, responseStudent);
    }

    public EvaluationCompareResponse startCompare(MultipartFile image, String studentCode) {
        UserAccount admin = SecurityUtils.currentUser();
        Student requestedStudent = resolveRequestedStudent(studentCode);
        StoredMultipartFile upload = snapshotUpload(image);
        EvaluationImage original = imageService.savePreAiUpload(upload, ImageType.ORIGINAL_IMAGE);

        EvaluationRun run = new EvaluationRun();
        run.setRequestedStudent(requestedStudent);
        run.setRequestedStudentCode(requestedStudent == null ? null : requestedStudent.getStudentCode());
        run.setOriginalImage(original);
        run.setMethod1Status(EvaluationProcessingStatus.PROCESSING);
        run.setMethod2Status(EvaluationProcessingStatus.PROCESSING);
        run.setCreatedBy(admin);
        evaluationRunRepository.save(run);

        String faceMode = requestedStudent == null ? "identify" : "verify";
        String aiStudentId = requestedStudent == null ? null : requestedStudent.getFaceDataId();
        launchMethodEvaluation(run.getId(), upload, aiStudentId, faceMode,
                EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE);
        launchMethodEvaluation(run.getId(), upload, aiStudentId, faceMode,
                EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);

        return toCompareResponse(run, requestedStudent);
    }

    @Transactional(readOnly = true)
    public EvaluationCompareResponse compareStatus(Long jobId) {
        EvaluationRun run = evaluationRunRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y job so s\u00e1nh: " + jobId));
        return toCompareResponse(run, responseStudent(run));
    }

    @Transactional
    public EvaluationHistoryResponse chooseOfficial(Long runId, ChooseOfficialRequest request) {
        EvaluationRun run = evaluationRunRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y l\u1ea7n ch\u1ea1y \u0111\u00e1nh gi\u00e1: " + runId));
        if (run.isOfficialSaved()) {
            throw new BadRequestException("L\u1ea7n ch\u1ea1y n\u00e0y \u0111\u00e3 \u0111\u01b0\u1ee3c l\u01b0u k\u1ebft qu\u1ea3 ch\u00ednh th\u1ee9c");
        }

        EvaluationMethod selectedMethod = parseSelectedMethod(request.selectedMethod());
        if (!methodStatus(run, selectedMethod).isTerminal()
                || methodStatus(run, selectedMethod) != EvaluationProcessingStatus.COMPLETED) {
            throw new BadRequestException("Ph\u01b0\u01a1ng ph\u00e1p \u0111\u00e3 ch\u1ecdn ch\u01b0a ho\u00e0n t\u1ea5t th\u00e0nh c\u00f4ng");
        }
        JsonNode selectedCandidate = readJson(selectedRawJson(run, selectedMethod));
        EvaluationImage selectedImage = selectedImage(run, selectedMethod);
        Student student = resolveOfficialStudent(run, request.studentCode(), selectedCandidate);
        Instant evaluatedAt = firstNonNull(methodCompletedAt(run, selectedMethod), run.getCreatedAt(), Instant.now());
        PreparedCandidate prepared = prepareCandidate(student, selectedCandidate, evaluatedAt);
        UniformComplianceService.UniformComplianceDecision decision = prepared.aiDecision();
        if (decision == null) {
            throw new BadRequestException("Không thể tạo quyết định tuân thủ từ kết quả AI đã chọn");
        }
        JsonNode officialJson = prepared.candidate();
        UniformComplianceScoreService.ScoreDecision scoreDecision = prepared.scoreDecision();

        EvaluationImage officialImage = null;
        if (selectedImage != null) {
            officialImage = imageService.saveBytes(
                    "official-" + selectedImage.getFileName(),
                    selectedImage.getContentType(),
                    selectedImage.getData(),
                    ImageType.OFFICIAL_PROCESSED
            );
        } else {
            officialImage = saveOfficialImageFromUrl(run, selectedMethod);
        }

        int automaticDeductedPoints = scoreDecision.automaticConductDeduction();
        int deductedPoints = request.deductedPoints() == null
                ? automaticDeductedPoints
                : request.deductedPoints();

        EvaluationHistory history = new EvaluationHistory();
        history.setStudent(student);
        history.setStudentCodeSnapshot(student.getStudentCode());
        history.setStudentNameSnapshot(student.getFullName());
        history.setClassNameSnapshot(student.getClassName());
        history.setDateOfBirthSnapshot(student.getDateOfBirth());
        history.setStudentAgeAtEvaluation(student.getAge());
        history.setRecognizedStudentCode(firstNonBlank(run.getRecognizedStudentCode(), extractor.recognizedStudentCode(officialJson)));
        history.setUniformAiEvaluationId(run.getUniformAiEvaluationId());
        history.setSelectedMethod(selectedMethod);
        history.setComplianceStatus(scoreDecision.complianceStatus());
        history.setHasWhiteShirt(decision.acceptedComponentKeys().contains(UniformComplianceService.WHITE_SHIRT));
        history.setHasYouthUnionShirt(decision.acceptedComponentKeys().contains(UniformComplianceService.YOUTH_UNION_SHIRT));
        history.setHasBlackTrousers(decision.acceptedComponentKeys().contains(UniformComplianceService.BLACK_TROUSERS));
        history.setHasRedScarf(decision.acceptedComponentKeys().contains(UniformComplianceService.RED_SCARF));
        history.setShirtTuckedIn(decision.shirtTuckedIn());
        history.setClothesWrinkled(decision.clothesWrinkled());
        history.setClothesDirty(decision.clothesDirty());
        history.setClothesTorn(decision.clothesTorn());
        history.setOverallCompliant(scoreDecision.complianceStatus() == ComplianceStatus.COMPLIANT);
        history.setViolationTypes(canonicalViolationTypes(decision, prepared.scheduleResult(), scoreDecision));
        history.setViolationSummary(canonicalViolationSummary(prepared.scheduleResult(), scoreDecision));
        history.setAiComment(decision.finalComment());
        history.setFlorenceDescription(decision.appearanceAssessment().path("description").asText(null));
        history.setAcceptedComponentsJson(toJson(decision.acceptedComponents()));
        history.setMissingComponentsJson(toJson(canonicalMissingComponents(decision, prepared.scheduleResult())));
        history.setRejectedComponentsJson(toJson(decision.rejectedComponents()));
        history.setTuckInAssessmentJson(toJson(decision.tuckInAssessment()));
        history.setAppearanceAssessmentJson(toJson(decision.appearanceAssessment()));
        history.setFinalScore(scoreDecision.canonicalScore());
        history.setFinalComment(scoreDecision.finalComment());
        applyScheduleSnapshot(history, prepared.scheduleResult());
        history.setRawMethod1Json(run.getRawMethod1Json());
        history.setRawMethod2Json(run.getRawMethod2Json());
        history.setOfficialResultJson(toJson(officialJson));
        history.setDeductedPoints(deductedPoints);
        history.setOriginalImage(run.getOriginalImage());
        history.setProcessedImage(officialImage);
        history.setPreAiImagePath(run.getPreAiImagePath());
        history.setPreAiImageUrl(run.getPreAiImageUrl());
        history.setSelectedProcessedImagePath(selectedProcessedImagePath(run, selectedMethod));
        history.setSelectedProcessedImageUrl(selectedProcessedImageUrl(run, selectedMethod));
        history.setComparisonRun(run);
        history.setCreatedBy(SecurityUtils.currentUser());
        history.setAdminNote(request.adminNote());
        evaluationHistoryRepository.save(history);

        moralityService.deduct(student, deductedPoints, "Tr\u1eeb \u0111i\u1ec3m do vi ph\u1ea1m \u0111\u1ed3ng ph\u1ee5c #" + history.getId(), history, SecurityUtils.currentUser());
        run.setOfficialSaved(true);
        evaluationRunRepository.save(run);

        return EvaluationHistoryResponse.from(history);
    }

    private JsonNode runIntegratedEvaluation(MultipartFile image, Student requestedStudent, String faceMode) {
        try {
            return uniformAiClient.evaluateStudentCandidates(
                    image,
                    requestedStudent == null ? null : requestedStudent.getFaceDataId(),
                    faceMode
            );
        } catch (Exception ex) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("success", false);
            error.put("message", "AI service unavailable");
            error.put("error", ex.getMessage());
            return error;
        }
    }

    private JsonNode runAdvancedEvaluation(
            MultipartFile image,
            Student requestedStudent,
            String faceMode,
            EvaluationMethod method
    ) {
        try {
            return uniformAiClient.evaluateAdvanced(
                    image,
                    requestedStudent == null ? null : requestedStudent.getFaceDataId(),
                    faceMode,
                    method.getCandidateKey()
            );
        } catch (Exception ex) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("success", false);
            error.put("message", "AI advanced evaluation unavailable");
            error.put("error", ex.getMessage());
            error.put("evaluation_method", method.getCandidateKey());
            return error;
        }
    }

    private JsonNode runLightweightEvaluation(MultipartFile image, Student requestedStudent, String faceMode) {
        try {
            return uniformAiClient.evaluateLightweight(
                    image,
                    requestedStudent == null ? null : requestedStudent.getFaceDataId(),
                    faceMode,
                    null
            );
        } catch (Exception ex) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("success", false);
            error.put("message", "AI lightweight evaluation unavailable");
            error.put("error", ex.getMessage());
            return error;
        }
    }
    private JsonNode runLightweightEvaluation(
            MultipartFile image,
            Student requestedStudent,
            String faceMode,
            EvaluationMethod method
    ) {
        try {
            return uniformAiClient.evaluateLightweight(
                    image,
                    requestedStudent == null ? null : requestedStudent.getFaceDataId(),
                    faceMode,
                    method.getCandidateKey()
            );
        } catch (Exception ex) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("success", false);
            error.put("message", "AI lightweight evaluation unavailable");
            error.put("error", ex.getMessage());
            error.put("evaluation_method", method.getCandidateKey());
            return error;
        }
    }

    private EvaluationMethod lightweightMethodFromSelection(String selectedMethod) {
        if (selectedMethod == null || selectedMethod.isBlank()) {
            return EvaluationMethod.METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM;
        }
        EvaluationMethod method;
        try {
            method = EvaluationMethod.fromSelection(selectedMethod);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        }
        return method.isMethod1Slot()
                ? EvaluationMethod.METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO
                : EvaluationMethod.METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM;
    }

    private Student resolveRequestedStudent(String studentCode) {
        if (studentCode == null || studentCode.isBlank()) {
            return null;
        }
        String normalized = studentCode.trim();
        return studentRepository.findByStudentCode(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y h\u1ecdc sinh: " + normalized));
    }

    private StoredMultipartFile snapshotUpload(MultipartFile image) {
        try {
            return new StoredMultipartFile(
                    "image",
                    cleanFileName(image.getOriginalFilename()),
                    image.getContentType() == null ? "application/octet-stream" : image.getContentType(),
                    image.getBytes()
            );
        } catch (IOException ex) {
            throw new BadRequestException("Kh\u00f4ng th\u1ec3 \u0111\u1ecdc \u1ea3nh t\u1ea3i l\u00ean");
        }
    }

    private void launchMethodEvaluation(
            Long runId,
            StoredMultipartFile upload,
            String aiStudentId,
            String faceMode,
            EvaluationMethod method
    ) {
        comparisonExecutor.submit(() -> evaluateMethodAsync(runId, upload, aiStudentId, faceMode, method));
    }

    private void evaluateMethodAsync(
            Long runId,
            StoredMultipartFile upload,
            String aiStudentId,
            String faceMode,
            EvaluationMethod method
    ) {
        try {
            JsonNode aiResponse = uniformAiClient.evaluateStudent(upload, aiStudentId, faceMode, uniformMethodParameter(method));
            if (!aiResponse.path("success").asBoolean(true)) {
                throw new IllegalStateException(aiResponse.path("message").asText("AI evaluation failed"));
            }
            JsonNode candidate = candidateOrError(aiResponse, method);
            if (candidate.path("available").isBoolean() && !candidate.path("available").asBoolean()) {
                throw new IllegalStateException(candidate.path("error").asText("AI candidate was not returned"));
            }

            completeMethod(runId, method, aiResponse, candidate);
        } catch (Exception ex) {
            failMethod(runId, method, ex);
        }
    }

    private void completeMethod(Long runId, EvaluationMethod method, JsonNode aiResponse, JsonNode candidate) {
        transactionTemplate.executeWithoutResult(status -> {
            EvaluationRun run = evaluationRunRepository.findByIdForUpdate(runId)
                    .orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y job so s\u00e1nh: " + runId));
            String recognizedCode = extractor.recognizedStudentCode(aiResponse);
            Student recognizedStudent = recognizedCode == null
                    ? null
                    : studentRepository.findByStudentCode(recognizedCode).orElse(null);
            if (run.getRecognizedStudentCode() == null && recognizedCode != null) {
                run.setRecognizedStudentCode(recognizedCode);
            }
            if (run.getRecognizedStudent() == null && recognizedStudent != null) {
                run.setRecognizedStudent(recognizedStudent);
            }
            if (run.getUniformAiEvaluationId() == null) {
                run.setUniformAiEvaluationId(extractor.uniformAiEvaluationId(aiResponse));
            }
            if (run.getPreAiImagePath() == null) {
                run.setPreAiImagePath(extractor.preAiImagePath(aiResponse));
            }
            if (run.getPreAiImageUrl() == null) {
                run.setPreAiImageUrl(uniformAiClient.resolveImageUrl(extractor.preAiImageUrl(aiResponse)));
            }

            Student responseStudent = recognizedStudent != null ? recognizedStudent : run.getRequestedStudent();
            Instant completedAt = Instant.now();
            PreparedCandidate prepared = prepareCandidate(responseStudent, candidate, completedAt);
            setMethodSuccess(run, method, prepared.candidate(), prepared.complianceStatus(), completedAt);
            if (selectedImage(run, method) == null) {
                ImageImportOutcome outcome = importProcessedImage(runId, method, prepared.candidate());
                setMethodImage(run, method, outcome.image());
                setMethodImageImportError(run, method, outcome.error());
                if (outcome.image() != null) {
                    setMethodProcessedImageUrl(run, method, managedImageUrl(outcome.image()));
                }
            }
            run.setRawAiResponseJson(toJson(aiResponse));
        });
    }

    private void failMethod(Long runId, EvaluationMethod method, Exception ex) {
        String message = safeErrorMessage(ex);
        JsonNode errorCandidate = errorCandidate(method, message);
        transactionTemplate.executeWithoutResult(status -> {
            EvaluationRun run = evaluationRunRepository.findById(runId)
                    .orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y job so s\u00e1nh: " + runId));
            setMethodFailure(run, method, errorCandidate, message);
        });
    }

    private JsonNode candidateOrError(JsonNode aiResponse, EvaluationMethod method) {
        JsonNode candidate = extractor.candidate(aiResponse, method);
        if (candidate != null && !candidate.isMissingNode() && !candidate.isNull()) {
            return candidate;
        }

        ObjectNode error = objectMapper.createObjectNode();
        error.put("method", method.getCandidateKey());
        error.put("method_display_name", method.getDisplayName());
        error.put("processed_image_url", (String) null);
        error.put("processed_image", (String) null);
        error.put("available", false);
        error.put("error", aiResponse.path("error").asText(aiResponse.path("message").asText("AI candidate was not returned")));
        ObjectNode result = error.putObject("result");
        result.put("method", method.getCandidateKey());
        result.set("accepted_components", objectMapper.createArrayNode());
        result.set("missing_components", objectMapper.createArrayNode());
        result.set("rejected_components", objectMapper.createArrayNode());
        ObjectNode summary = result.putObject("final_summary");
        summary.put("is_compliant", false);
        summary.put("score", 0);
        summary.put("vietnamese_comment", "Kh\u00f4ng nh\u1eadn \u0111\u01b0\u1ee3c k\u1ebft qu\u1ea3 \u1ee9ng vi\u00ean t\u1eeb Uniform AI.");
        return error;
    }

    private JsonNode errorCandidate(EvaluationMethod method, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("method", method.getCandidateKey());
        error.put("method_display_name", method.getDisplayName());
        error.put("processed_image_url", (String) null);
        error.put("processed_image", (String) null);
        error.put("available", false);
        error.put("error", message);
        ObjectNode result = error.putObject("result");
        result.put("method", method.getCandidateKey());
        result.set("accepted_components", objectMapper.createArrayNode());
        result.set("missing_components", objectMapper.createArrayNode());
        result.set("rejected_components", objectMapper.createArrayNode());
        ObjectNode summary = result.putObject("final_summary");
        summary.put("is_compliant", false);
        summary.put("score", 0);
        summary.put("vietnamese_comment", message);
        return error;
    }

    private void setMethodSuccess(
            EvaluationRun run,
            EvaluationMethod method,
            JsonNode candidate,
            ComplianceStatus complianceStatus
    ) {
        setMethodSuccess(run, method, candidate, complianceStatus, Instant.now());
    }

    private void setMethodSuccess(
            EvaluationRun run,
            EvaluationMethod method,
            JsonNode candidate,
            ComplianceStatus complianceStatus,
            Instant completedAt
    ) {
        if (method.isMethod1Slot()) {
            run.setMethod1Compliance(complianceStatus);
            run.setMethod1ProcessedImagePath(extractor.processedImagePath(candidate));
            run.setMethod1ProcessedImageUrl(uniformAiClient.resolveImageUrl(extractor.processedImageUrl(candidate)));
            run.setRawMethod1Json(toJson(candidate));
            run.setMethod1ScheduleSnapshotJson(scheduleSnapshotJson(candidate));
            run.setMethod1Status(EvaluationProcessingStatus.COMPLETED);
            run.setMethod1Error(null);
            run.setMethod1CompletedAt(completedAt);
        } else {
            run.setMethod2Compliance(complianceStatus);
            run.setMethod2ProcessedImagePath(extractor.processedImagePath(candidate));
            run.setMethod2ProcessedImageUrl(uniformAiClient.resolveImageUrl(extractor.processedImageUrl(candidate)));
            run.setRawMethod2Json(toJson(candidate));
            run.setMethod2ScheduleSnapshotJson(scheduleSnapshotJson(candidate));
            run.setMethod2Status(EvaluationProcessingStatus.COMPLETED);
            run.setMethod2Error(null);
            run.setMethod2CompletedAt(completedAt);
        }
    }

    private void setMethodFailure(EvaluationRun run, EvaluationMethod method, JsonNode candidate, String message) {
        if (method.isMethod1Slot()) {
            run.setMethod1Compliance(ComplianceStatus.NEEDS_REVIEW);
            run.setRawMethod1Json(toJson(candidate));
            run.setMethod1Status(EvaluationProcessingStatus.FAILED);
            run.setMethod1Error(message);
            run.setMethod1CompletedAt(Instant.now());
        } else {
            run.setMethod2Compliance(ComplianceStatus.NEEDS_REVIEW);
            run.setRawMethod2Json(toJson(candidate));
            run.setMethod2Status(EvaluationProcessingStatus.FAILED);
            run.setMethod2Error(message);
            run.setMethod2CompletedAt(Instant.now());
        }
    }

    private EvaluationProcessingStatus methodStatus(EvaluationRun run, EvaluationMethod method) {
        EvaluationProcessingStatus status = method.isMethod1Slot()
                ? run.getMethod1Status()
                : run.getMethod2Status();
        if (status == null) {
            String rawJson = method.isMethod1Slot()
                    ? run.getRawMethod1Json()
                    : run.getRawMethod2Json();
            if (rawJson != null && !rawJson.isBlank()) {
                return EvaluationProcessingStatus.COMPLETED;
            }
        }
        return normalizedStatus(status);
    }

    private String methodError(EvaluationRun run, EvaluationMethod method) {
        return method.isMethod1Slot()
                ? run.getMethod1Error()
                : run.getMethod2Error();
    }

    private Instant methodCompletedAt(EvaluationRun run, EvaluationMethod method) {
        return method.isMethod1Slot()
                ? run.getMethod1CompletedAt()
                : run.getMethod2CompletedAt();
    }

    private String uniformMethodParameter(EvaluationMethod method) {
        return method.isMethod1Slot() ? "grounding_dino_v2" : "yolov8_v2";
    }

    private String fallbackProcessedImageName(EvaluationMethod method) {
        return method.isMethod1Slot()
                ? "method-1-processed.jpg"
                : "method-2-processed.jpg";
    }

    private ComplianceStatus compareCompliance(Student student, JsonNode candidate) {
        if (student == null) {
            return extractor.candidateComplianceStatus(candidate);
        }
        try {
            return complianceService.evaluate(student, candidate).complianceStatus();
        } catch (Exception ignored) {
            return extractor.candidateComplianceStatus(candidate);
        }
    }

    private PreparedCandidate prepareCandidate(Student student, JsonNode candidate, Instant evaluatedAt) {
        UniformComplianceService.UniformComplianceDecision aiDecision = null;
        JsonNode enriched = candidate;
        ComplianceStatus extractorStatus = extractor.candidateComplianceStatus(candidate);
        ComplianceStatus aiStatus;
        try {
            aiDecision = complianceService.evaluate(student, candidate);
            aiStatus = extractorStatus == ComplianceStatus.NEEDS_REVIEW
                    ? ComplianceStatus.NEEDS_REVIEW
                    : aiDecision.complianceStatus();
            enriched = complianceService.withBackendDecision(candidate, student, aiDecision);
        } catch (Exception ex) {
            aiStatus = extractorStatus;
        }

        ScheduleComplianceResult scheduleResult = scheduleService.evaluate(student, enriched, evaluatedAt);
        UniformComplianceScoreService.ScoreDecision scoreDecision = scoreService.decide(scheduleResult, aiDecision, aiStatus);
        JsonNode finalCandidate = scheduleService.withScheduleResult(enriched, scheduleResult, scoreDecision);
        return new PreparedCandidate(finalCandidate, scoreDecision.complianceStatus(), aiStatus, aiDecision, scheduleResult, scoreDecision);
    }

    private void applyScheduleSnapshot(EvaluationHistory history, ScheduleComplianceResult scheduleResult) {
        if (scheduleResult == null) {
            return;
        }
        JsonNode scheduleJson = scheduleService.toJson(scheduleResult);
        history.setScheduleConfigured(scheduleResult.configured());
        history.setScheduleApplicable(scheduleResult.applicable());
        history.setScheduleReason(scheduleResult.reason());
        history.setScheduleClassName(scheduleResult.className());
        history.setScheduleDayOfWeek(scheduleResult.dayOfWeek() == null ? null : scheduleResult.dayOfWeek().name());
        history.setScheduleDayLabel(scheduleResult.dayLabel());
        history.setScheduleTimeZone(scheduleResult.timeZone());
        history.setScheduleEvaluatedAt(scheduleResult.evaluatedAt());
        history.setScheduleScore(scheduleResult.score());
        history.setScheduleDeductedPoints(scheduleResult.deductedPoints());
        history.setScheduleRequiredComponentsJson(toJson(scheduleJson.path("requiredComponents")));
        history.setScheduleDetectedComponentsJson(toJson(scheduleJson.path("detectedComponents")));
        history.setScheduleMissingComponentsJson(toJson(scheduleJson.path("missingComponents")));
        history.setScheduleSnapshotJson(toJson(scheduleResult.snapshot()));
    }

    private String scheduleSnapshotJson(JsonNode candidate) {
        JsonNode result = candidate == null ? NullNode.getInstance() : extractor.candidateResult(candidate);
        JsonNode scheduleResult = result.path(UniformRequirementScheduleService.SCHEDULE_RESULT_FIELD);
        if (scheduleResult.isMissingNode() || scheduleResult.isNull()) {
            scheduleResult = candidate == null
                    ? NullNode.getInstance()
                    : candidate.path(UniformRequirementScheduleService.SCHEDULE_RESULT_FIELD);
        }
        return scheduleResult.isMissingNode() || scheduleResult.isNull() ? null : toJson(scheduleResult);
    }

    private EvaluationImage savePreAiImage(JsonNode aiResponse, MultipartFile fallbackUpload) {
        String preAiUrl = extractor.preAiImageUrl(aiResponse);
        if (preAiUrl != null) {
            try {
                byte[] bytes = uniformAiClient.downloadImage(preAiUrl);
                return imageService.saveBytes(
                        "pre-ai-" + cleanFileName(fallbackUpload.getOriginalFilename()),
                        fallbackUpload.getContentType() == null ? "image/jpeg" : fallbackUpload.getContentType(),
                        bytes,
                        ImageType.ORIGINAL_IMAGE
                );
            } catch (Exception ignored) {
                // Fall back to backend-side compression below.
            }
        }
        return imageService.savePreAiUpload(fallbackUpload, ImageType.ORIGINAL_IMAGE);
    }

    private ImageImportOutcome importProcessedImage(Long runId, EvaluationMethod method, JsonNode candidate) {
        try {
            return new ImageImportOutcome(processedImageImporter.importProcessedImage(runId, method, candidate), null);
        } catch (Exception ex) {
            String error = safeErrorMessage(ex);
            log.error(
                    "event=ai_processed_image_import_preserved_evaluation runId={} method={} reason={}",
                    runId, method.getCandidateKey(), error
            );
            return new ImageImportOutcome(null, error);
        }
    }

    private void setMethodImage(EvaluationRun run, EvaluationMethod method, EvaluationImage image) {
        if (method.isMethod1Slot()) {
            run.setMethod1Image(image);
        } else {
            run.setMethod2Image(image);
        }
    }

    private void setMethodProcessedImageUrl(EvaluationRun run, EvaluationMethod method, String imageUrl) {
        if (method.isMethod1Slot()) {
            run.setMethod1ProcessedImageUrl(imageUrl);
        } else {
            run.setMethod2ProcessedImageUrl(imageUrl);
        }
    }

    private void setMethodImageImportError(EvaluationRun run, EvaluationMethod method, String error) {
        if (method.isMethod1Slot()) {
            run.setMethod1Error(error);
        } else {
            run.setMethod2Error(error);
        }
    }

    private String managedImageUrl(EvaluationImage image) {
        return image == null || image.getId() == null ? null : "/api/images/" + image.getId();
    }

    private EvaluationCompareResponse toCompareResponse(EvaluationRun run, Student responseStudent) {
        EvaluationMethod method1Type = responseMethodForSlot(run, true);
        EvaluationMethod method2Type = responseMethodForSlot(run, false);
        MethodResultResponse method1Response = toMethodResponse(
                method1Type,
                run.getMethod1Compliance(),
                run.getMethod1Image(),
                readJsonOrNull(run.getRawMethod1Json()),
                methodStatus(run, method1Type),
                run.getMethod1Error(),
                run.getMethod1CompletedAt()
        );
        MethodResultResponse method2Response = toMethodResponse(
                method2Type,
                run.getMethod2Compliance(),
                run.getMethod2Image(),
                readJsonOrNull(run.getRawMethod2Json()),
                methodStatus(run, method2Type),
                run.getMethod2Error(),
                run.getMethod2CompletedAt()
        );
        Long originalImageId = run.getOriginalImage() == null ? null : run.getOriginalImage().getId();
        String originalImageUrl = originalImageId == null ? null : "/api/images/" + originalImageId;
        List<MethodResultResponse> results = responseResults(run, method1Response, method2Response);
        return new EvaluationCompareResponse(
                run.getId(),
                run.getRequestedStudentCode(),
                run.getRecognizedStudentCode(),
                originalImageId,
                method1Response,
                method2Response,
                run.getCreatedAt(),
                run.getUniformAiEvaluationId(),
                null,
                originalImageUrl,
                originalImageUrl,
                responseStudent == null ? null : StudentResponse.from(responseStudent),
                results,
                run.getId(),
                jobStatus(run, results),
                run.getUpdatedAt(),
                results
        );
    }

    private List<MethodResultResponse> responseResults(
            EvaluationRun run,
            MethodResultResponse method1Response,
            MethodResultResponse method2Response
    ) {
        List<EvaluationMethod> requestedMethods = aiCandidateMethods(run);
        if (requestedMethods.isEmpty() || requestedMethods.size() >= 2) {
            return List.of(method1Response, method2Response);
        }
        EvaluationMethod method = requestedMethods.get(0);
        return List.of(method.isMethod1Slot()
                ? method1Response
                : method2Response);
    }

    private EvaluationMethod responseMethodForSlot(EvaluationRun run, boolean method1Slot) {
        for (EvaluationMethod method : aiCandidateMethods(run)) {
            if (method1Slot && method.isMethod1Slot()) {
                return method;
            }
            if (!method1Slot && method.isMethod2Slot()) {
                return method;
            }
        }
        return method1Slot
                ? EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE
                : EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE;
    }

    private List<EvaluationMethod> aiCandidateMethods(EvaluationRun run) {
        JsonNode raw = readJsonOrNull(run.getRawAiResponseJson());
        JsonNode uniform = extractor.uniformPayload(raw);
        List<EvaluationMethod> methods = new ArrayList<>();
        appendCandidateMethods(methods, raw.path("candidate_methods"));
        appendCandidateMethods(methods, uniform.path("candidate_methods"));
        appendCandidateMethod(methods, raw.path("default_candidate_method"));
        appendCandidateMethod(methods, uniform.path("default_candidate_method"));
        JsonNode candidates = raw.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                appendCandidateMethod(methods, candidate.path("method"));
            }
        }
        JsonNode uniformCandidates = uniform.path("candidates");
        if (uniformCandidates.isArray()) {
            for (JsonNode candidate : uniformCandidates) {
                appendCandidateMethod(methods, candidate.path("method"));
            }
        }
        return methods;
    }

    private void appendCandidateMethods(List<EvaluationMethod> methods, JsonNode values) {
        if (!values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            appendCandidateMethod(methods, value);
        }
    }

    private void appendCandidateMethod(List<EvaluationMethod> methods, JsonNode value) {
        if (!value.isTextual()) {
            return;
        }
        try {
            EvaluationMethod method = EvaluationMethod.fromSelection(value.asText());
            if (!methods.contains(method)) {
                methods.add(method);
            }
        } catch (IllegalArgumentException ignored) {
            // Ignore unknown AI method keys and fall back to the legacy two-slot response.
        }
    }

    private MethodResultResponse toMethodResponse(
            EvaluationMethod method,
            ComplianceStatus status,
            EvaluationImage image,
            JsonNode raw,
            EvaluationProcessingStatus processingStatus,
            String error,
        Instant completedAt
    ) {
        Long imageId = image == null ? null : image.getId();
        String managedImageUrl = imageId == null ? null : "/api/images/" + imageId;
        JsonNode browserRaw = browserSafeJson(raw, managedImageUrl);
        JsonNode result = browserRaw == null ? NullNode.getInstance() : extractor.candidateResult(browserRaw);
        Integer score = score(raw, result);
        return new MethodResultResponse(
                method,
                status,
                imageId,
                managedImageUrl,
                managedImageUrl,
                browserRaw,
                method.getCandidateKey(),
                method.getDisplayName(),
                null,
                result.isMissingNode() ? NullNode.getInstance() : result,
                apiStatus(processingStatus),
                score,
                resultStatus(status),
                arrayOrEmpty(result.path("accepted_components")),
                arrayOrEmpty(result.path("missing_components")),
                arrayOrEmpty(result.path("rejected_components")),
                methodMessage(processingStatus, result, error),
                raw == null ? null : extractor.notes(raw),
                error,
                completedAt,
                firstText(result.path("method"), raw.path("method")),
                firstText(result.path("detector_model_id"), raw.path("detector_model_id")),
                firstText(result.path("detector_model_version"), raw.path("detector_model_version")),
                firstJson(result.path("detector_confidence_threshold"), raw.path("detector_confidence_threshold")),
                firstInteger(result.path("raw_detection_count"), raw.path("raw_detection_count")),
                firstInteger(result.path("pose_accepted_detection_count"), raw.path("pose_accepted_detection_count")),
                firstInteger(result.path("final_unique_detection_count"), raw.path("final_unique_detection_count")),
                firstInteger(result.path("duplicate_removed_count"), raw.path("duplicate_removed_count")),
                firstJson(result.path(UniformRequirementScheduleService.SCHEDULE_RESULT_FIELD),
                        raw.path(UniformRequirementScheduleService.SCHEDULE_RESULT_FIELD)),
                firstJson(result.path("detector_trace"), raw.path("detector_trace"))
        );
    }

    private String jobStatus(EvaluationRun run, List<MethodResultResponse> responseResults) {
        if (responseResults.size() == 1) {
            return responseResults.get(0).status();
        }
        EvaluationProcessingStatus method1 = methodStatus(run, EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE);
        EvaluationProcessingStatus method2 = methodStatus(run, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);
        if (method1.isTerminal() && method2.isTerminal()) {
            return method1 == EvaluationProcessingStatus.COMPLETED || method2 == EvaluationProcessingStatus.COMPLETED
                    ? "completed"
                    : "failed";
        }
        if (method1.isTerminal() || method2.isTerminal()) {
            return "partial";
        }
        return "processing";
    }

    private EvaluationProcessingStatus normalizedStatus(EvaluationProcessingStatus status) {
        return status == null ? EvaluationProcessingStatus.PENDING : status;
    }

    private String apiStatus(EvaluationProcessingStatus status) {
        return normalizedStatus(status).getApiValue();
    }

    private Integer score(JsonNode raw, JsonNode result) {
        JsonNode scheduleScore = result.path(UniformRequirementScheduleService.SCHEDULE_RESULT_FIELD).path("score");
        if (scheduleScore.isNumber()) {
            return scheduleScore.asInt();
        }
        JsonNode canonicalBackendScore = result.path("backend_final_result").path("canonical_score");
        if (canonicalBackendScore.isNumber()) {
            return canonicalBackendScore.asInt();
        }
        JsonNode backendScore = result.path("backend_final_result").path("finalScore");
        if (backendScore.isNumber()) {
            return backendScore.asInt();
        }
        JsonNode snakeBackendScore = result.path("backend_final_result").path("final_score");
        if (snakeBackendScore.isNumber()) {
            return snakeBackendScore.asInt();
        }
        return null;
    }

    private String resultStatus(ComplianceStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case COMPLIANT -> "\u0110\u1ea1t";
            case NON_COMPLIANT -> "Ch\u01b0a \u0111\u1ea1t";
            case PARTIALLY_COMPLIANT, NEEDS_REVIEW -> "C\u1ea7n ki\u1ec3m tra l\u1ea1i";
        };
    }

    private JsonNode arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray() ? node : objectMapper.createArrayNode();
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String text = extractor.textOrNull(node);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private Integer firstInteger(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isIntegralNumber()) {
                return node.asInt();
            }
        }
        return null;
    }

    private JsonNode firstJson(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return NullNode.getInstance();
    }

    private JsonNode browserSafeJson(JsonNode node, String managedImageUrl) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        JsonNode copy = node.deepCopy();
        sanitizeBrowserImageFields(copy, managedImageUrl);
        return copy;
    }

    private void sanitizeBrowserImageFields(JsonNode node, String managedImageUrl) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode value = object.get(fieldName);
                if (isImageUrlField(fieldName)) {
                    String resolved = managedImageUrl != null && isProcessedImageUrlField(fieldName)
                            ? managedImageUrl
                            : value != null && value.isTextual()
                                    ? uniformAiClient.resolveImageUrl(value.asText())
                                    : null;
                    if (resolved == null) {
                        object.putNull(fieldName);
                    } else {
                        object.put(fieldName, resolved);
                    }
                } else if (isImagePathField(fieldName)) {
                    object.putNull(fieldName);
                } else if (value != null && value.isTextual() && isLocalFileSystemPath(value.asText())) {
                    object.putNull(fieldName);
                } else {
                    sanitizeBrowserImageFields(value, managedImageUrl);
                }
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                sanitizeBrowserImageFields(child, managedImageUrl);
            }
        }
    }

    private boolean isImageUrlField(String fieldName) {
        String normalized = fieldName.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.contains("imageurl");
    }

    private boolean isProcessedImageUrlField(String fieldName) {
        String normalized = fieldName.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.contains("imageurl")
                && !normalized.contains("originalimageurl")
                && !normalized.contains("preaiimageurl");
    }

    private boolean isImagePathField(String fieldName) {
        String normalized = fieldName.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.contains("imagepath") || normalized.equals("processedimage") || normalized.endsWith("path");
    }

    private boolean isLocalFileSystemPath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().replace('\\', '/');
        return normalized.matches("^[A-Za-z]:/.*") || normalized.startsWith("/");
    }

    private String methodMessage(EvaluationProcessingStatus status, JsonNode result, String error) {
        EvaluationProcessingStatus normalized = normalizedStatus(status);
        if (normalized == EvaluationProcessingStatus.FAILED) {
            return error == null || error.isBlank() ? "AI method failed" : error;
        }
        if (normalized == EvaluationProcessingStatus.PROCESSING || normalized == EvaluationProcessingStatus.PENDING) {
            return "\u0110ang x\u1eed l\u00fd";
        }
        String backendComment = extractor.textOrNull(result.path("backend_final_result").path("finalComment"));
        if (backendComment != null) {
            return backendComment;
        }
        String snakeBackendComment = extractor.textOrNull(result.path("backend_final_result").path("final_comment"));
        if (snakeBackendComment != null) {
            return snakeBackendComment;
        }
        return extractor.textOrNull(result.path("final_summary").path("vietnamese_comment"));
    }

    private JsonNode readJsonOrNull(String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return NullNode.getInstance();
        }
    }

    private Student responseStudent(EvaluationRun run) {
        if (run.getRecognizedStudent() != null) {
            return run.getRecognizedStudent();
        }
        if (run.getRequestedStudent() != null) {
            return run.getRequestedStudent();
        }
        if (run.getRecognizedStudentCode() != null) {
            return studentRepository.findByStudentCode(run.getRecognizedStudentCode()).orElse(null);
        }
        return null;
    }

    private Student resolveOfficialStudent(EvaluationRun run, String overrideCode, JsonNode officialJson) {
        if (overrideCode != null && !overrideCode.isBlank()) {
            return studentRepository.findByStudentCode(overrideCode)
                    .orElseThrow(() -> new ResourceNotFoundException("Kh\u00f4ng t\u00ecm th\u1ea5y h\u1ecdc sinh: " + overrideCode));
        }
        if (run.getRecognizedStudent() != null) {
            return run.getRecognizedStudent();
        }
        if (run.getRequestedStudent() != null) {
            return run.getRequestedStudent();
        }
        String recognized = extractor.recognizedStudentCode(officialJson);
        if (recognized != null) {
            Student student = studentRepository.findByStudentCode(recognized).orElse(null);
            if (student != null) {
                return student;
            }
        }
        throw new BadRequestException("Kh\u00f4ng x\u00e1c \u0111\u1ecbnh \u0111\u01b0\u1ee3c h\u1ecdc sinh cho k\u1ebft qu\u1ea3 ch\u00ednh th\u1ee9c");
    }

    private EvaluationMethod parseSelectedMethod(String selectedMethod) {
        try {
            return EvaluationMethod.fromSelection(selectedMethod);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        }
    }

    private String selectedRawJson(EvaluationRun run, EvaluationMethod method) {
        return method.isMethod1Slot()
                ? run.getRawMethod1Json()
                : run.getRawMethod2Json();
    }

    private EvaluationImage selectedImage(EvaluationRun run, EvaluationMethod method) {
        return method.isMethod1Slot()
                ? run.getMethod1Image()
                : run.getMethod2Image();
    }

    private String selectedProcessedImagePath(EvaluationRun run, EvaluationMethod method) {
        return method.isMethod1Slot()
                ? run.getMethod1ProcessedImagePath()
                : run.getMethod2ProcessedImagePath();
    }

    private String selectedProcessedImageUrl(EvaluationRun run, EvaluationMethod method) {
        return method.isMethod1Slot()
                ? run.getMethod1ProcessedImageUrl()
                : run.getMethod2ProcessedImageUrl();
    }

    private EvaluationImage saveOfficialImageFromUrl(EvaluationRun run, EvaluationMethod method) {
        String imageUrl = selectedProcessedImageUrl(run, method);
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = uniformAiClient.downloadImage(imageUrl);
            return imageService.saveBytes(
                    "official-" + fallbackProcessedImageName(method),
                    "image/jpeg",
                    bytes,
                    ImageType.OFFICIAL_PROCESSED
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            throw new BadRequestException("Kh\u00f4ng \u0111\u1ecdc \u0111\u01b0\u1ee3c JSON k\u1ebft qu\u1ea3 AI");
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new BadRequestException("Kh\u00f4ng \u0111\u1ecdc \u0111\u01b0\u1ee3c JSON k\u1ebft qu\u1ea3 AI");
        }
    }

    private String toJson(JsonNode json) {
        try {
            return objectMapper.writeValueAsString(json);
        } catch (Exception ex) {
            return String.valueOf(json);
        }
    }

    private String cleanFileName(String name) {
        return name == null || name.isBlank() ? "image.jpg" : name.replace("\\", "_").replace("/", "_");
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private Instant firstNonNull(Instant... values) {
        for (Instant value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Set<String> canonicalViolationTypes(
            UniformComplianceService.UniformComplianceDecision decision,
            ScheduleComplianceResult scheduleResult,
            UniformComplianceScoreService.ScoreDecision scoreDecision
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (decision != null && decision.violationTypes() != null) {
            values.addAll(decision.violationTypes());
        }
        if (scheduleResult != null && scheduleResult.missingComponents() != null) {
            scheduleResult.missingComponents().stream()
                    .map(value -> "MISSING_SCHEDULE_" + value.toUpperCase(Locale.ROOT))
                    .forEach(values::add);
        }
        if (scoreDecision != null && scoreDecision.reviewReasons() != null) {
            values.addAll(scoreDecision.reviewReasons());
        }
        return values;
    }

    private String canonicalViolationSummary(
            ScheduleComplianceResult scheduleResult,
            UniformComplianceScoreService.ScoreDecision scoreDecision
    ) {
        if (scoreDecision == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (scheduleResult != null && scheduleResult.applicable() && !scheduleResult.missingComponents().isEmpty()) {
            parts.add("Thiếu theo lịch lớp: " + String.join(", ", scheduleResult.missingComponents()));
        }
        if (scoreDecision.reviewIssue() && !scoreDecision.reviewReasons().isEmpty()) {
            parts.add("Cần kiểm tra: " + String.join(", ", scoreDecision.reviewReasons()));
        }
        if (parts.isEmpty()) {
            return scoreDecision.complianceStatus() == ComplianceStatus.COMPLIANT
                    ? "Đủ thành phần theo lịch đồng phục lớp."
                    : scoreDecision.finalComment();
        }
        return String.join("; ", parts);
    }

    private JsonNode canonicalMissingComponents(
            UniformComplianceService.UniformComplianceDecision decision,
            ScheduleComplianceResult scheduleResult
    ) {
        if (scheduleResult != null && scheduleResult.applicable()) {
            return scheduleService.toJson(scheduleResult).path("missingComponents");
        }
        return decision == null ? objectMapper.createArrayNode() : decision.missingComponents();
    }

    private String safeErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() <= ERROR_MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }

    private record ImageImportOutcome(EvaluationImage image, String error) {
    }

    private record PreparedCandidate(
            JsonNode candidate,
            ComplianceStatus complianceStatus,
            ComplianceStatus aiComplianceStatus,
            UniformComplianceService.UniformComplianceDecision aiDecision,
            ScheduleComplianceResult scheduleResult,
            UniformComplianceScoreService.ScoreDecision scoreDecision
    ) {
    }

    private static final class StoredMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] bytes;

        private StoredMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
