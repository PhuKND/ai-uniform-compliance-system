package com.uniform.management.uniformschedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uniform.management.common.BadRequestException;
import com.uniform.management.common.ResourceNotFoundException;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.evaluation.UniformComplianceScoreService;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.uniformschedule.dto.UniformClassResponse;
import com.uniform.management.uniformschedule.dto.UniformComponentOption;
import com.uniform.management.uniformschedule.dto.StudentUniformScheduleComponentResponse;
import com.uniform.management.uniformschedule.dto.StudentUniformScheduleDayResponse;
import com.uniform.management.uniformschedule.dto.StudentUniformScheduleResponse;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleDayRequest;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleDayResponse;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleResponse;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleUpdateRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UniformRequirementScheduleService {

    public static final String SCHEDULE_RESULT_FIELD = "backend_schedule_result";
    public static final String FINAL_RESULT_FIELD = "backend_final_result";

    private static final List<DayOfWeek> WEEKDAYS = List.of(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
    );

    private final UniformRequirementScheduleRepository scheduleRepository;
    private final StudentRepository studentRepository;
    private final UniformComplianceScoreService scoreService;
    private final ObjectMapper objectMapper;
    private final ZoneId scheduleZoneId;

    public UniformRequirementScheduleService(
            UniformRequirementScheduleRepository scheduleRepository,
            StudentRepository studentRepository,
            UniformComplianceScoreService scoreService,
            ObjectMapper objectMapper,
            @Value("${uniform.schedule.time-zone:Asia/Ho_Chi_Minh}") String scheduleTimeZone
    ) {
        this.scheduleRepository = scheduleRepository;
        this.studentRepository = studentRepository;
        this.scoreService = scoreService;
        this.objectMapper = objectMapper;
        this.scheduleZoneId = ZoneId.of(scheduleTimeZone);
    }

    @Transactional(readOnly = true)
    public List<UniformClassResponse> listClasses() {
        return studentRepository.countStudentsByClass().stream()
                .map(row -> new ClassCount((String) row[0], ((Number) row[1]).longValue()))
                .filter(row -> row.className() != null && !row.className().isBlank())
                .sorted((left, right) -> left.className().compareToIgnoreCase(right.className()))
                .map(row -> new UniformClassResponse(row.className(), row.className(), row.studentCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public UniformRequirementScheduleResponse getWeeklySchedule(String classId) {
        String className = resolveExistingClassName(classId);
        List<UniformRequirementSchedule> schedules = scheduleRepository.findByClassNameOrderByDayOfWeekAsc(className);
        return toWeeklyResponse(className, schedules);
    }

    @Transactional(readOnly = true)
    public StudentUniformScheduleResponse getStudentWeeklySchedule(Student student) {
        String className = student == null ? null : cleanClassName(student.getClassName());
        Map<DayOfWeek, UniformRequirementSchedule> byDay = className == null
                ? Map.of()
                : scheduleRepository.findByClassNameOrderByDayOfWeekAsc(className).stream()
                        .collect(Collectors.toMap(UniformRequirementSchedule::getDayOfWeek, Function.identity(), (left, right) -> left));
        DayOfWeek today = LocalDateTime.now(scheduleZoneId).getDayOfWeek();
        List<StudentUniformScheduleDayResponse> days = WEEKDAYS.stream()
                .map(day -> toStudentDayResponse(day, byDay.get(day), today))
                .toList();
        return new StudentUniformScheduleResponse(
                student == null ? null : student.getStudentCode(),
                student == null ? null : student.getStudentCode(),
                student == null ? null : student.getFullName(),
                className,
                scheduleZoneId.getId(),
                today,
                days
        );
    }

    @Transactional
    public UniformRequirementScheduleResponse updateWeeklySchedule(
            String classId,
            UniformRequirementScheduleUpdateRequest request
    ) {
        String className = resolveExistingClassName(classId);
        Map<DayOfWeek, Set<UniformComponent>> requested = validateWeeklyRequest(request);
        Map<DayOfWeek, UniformRequirementSchedule> existing = scheduleRepository.findByClassNameOrderByDayOfWeekAsc(className)
                .stream()
                .collect(Collectors.toMap(UniformRequirementSchedule::getDayOfWeek, Function.identity()));

        List<UniformRequirementSchedule> saved = new ArrayList<>();
        for (DayOfWeek day : WEEKDAYS) {
            UniformRequirementSchedule schedule = existing.getOrDefault(day, new UniformRequirementSchedule());
            schedule.setClassName(className);
            schedule.setDayOfWeek(day);
            schedule.setRequiredComponents(new LinkedHashSet<>(requested.get(day)));
            saved.add(scheduleRepository.save(schedule));
        }
        return toWeeklyResponse(className, saved);
    }

    @Transactional(readOnly = true)
    public ScheduleComplianceResult evaluate(Student student, JsonNode candidate, Instant evaluationInstant) {
        Instant evaluatedAt = evaluationInstant == null ? Instant.now() : evaluationInstant;
        DayOfWeek dayOfWeek = LocalDateTime.ofInstant(evaluatedAt, scheduleZoneId).getDayOfWeek();
        if (student == null) {
            return notApplicable("student_not_resolved", null, dayOfWeek, evaluatedAt);
        }
        String className = cleanClassName(student.getClassName());
        if (className == null) {
            return notApplicable("student_class_not_resolved", null, dayOfWeek, evaluatedAt);
        }

        UniformRequirementSchedule schedule = scheduleRepository.findByClassNameAndDayOfWeek(className, dayOfWeek)
                .orElse(null);
        if (schedule == null) {
            return notApplicable("weekday_schedule_not_configured", className, dayOfWeek, evaluatedAt);
        }

        Set<String> detected = detectedComponentKeys(candidate);
        List<String> required = orderedKeys(schedule.getRequiredComponents());
        List<String> missing = required.stream().filter(key -> !detected.contains(key)).toList();
        int score = scoreService.scheduleComplianceScore(missing.size());
        ComplianceStatus status = scoreService.statusForScore(score, false);
        int conductDeduction = scoreService.automaticConductDeduction(score);
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("className", className);
        snapshot.put("dayOfWeek", dayOfWeek.name());
        snapshot.put("dayLabel", dayLabel(dayOfWeek));
        snapshot.put("timeZone", scheduleZoneId.getId());
        snapshot.put("evaluatedAt", evaluatedAt.toString());
        snapshot.set("requiredComponents", stringArray(required));

        return new ScheduleComplianceResult(
                true,
                true,
                null,
                className,
                dayOfWeek,
                dayLabel(dayOfWeek),
                scheduleZoneId.getId(),
                evaluatedAt,
                required,
                new ArrayList<>(detected),
                missing,
                missing.size(),
                score,
                conductDeduction,
                status,
                snapshot
        );
    }

    public ScheduleComplianceResult identityNeedsReview(Student student, Instant evaluationInstant) {
        Instant evaluatedAt = evaluationInstant == null ? Instant.now() : evaluationInstant;
        DayOfWeek dayOfWeek = LocalDateTime.ofInstant(evaluatedAt, scheduleZoneId).getDayOfWeek();
        String className = student == null ? null : cleanClassName(student.getClassName());
        return notApplicable("identity_needs_review", className, dayOfWeek, evaluatedAt);
    }

    public JsonNode withScheduleResult(
            JsonNode candidate,
            ScheduleComplianceResult scheduleResult,
            UniformComplianceScoreService.ScoreDecision scoreDecision
    ) {
        ObjectNode copy = candidate != null && candidate.isObject()
                ? (ObjectNode) candidate.deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode resultObject = candidateResultObject(copy);
        ObjectNode scheduleNode = toJson(scheduleResult);
        resultObject.set(SCHEDULE_RESULT_FIELD, scheduleNode);
        copy.set(SCHEDULE_RESULT_FIELD, scheduleNode.deepCopy());

        ComplianceStatus finalStatus = scoreDecision == null ? null : scoreDecision.complianceStatus();
        Integer canonicalScore = scoreDecision == null ? null : scoreDecision.canonicalScore();
        Integer conductDeduction = scoreDecision == null ? null : scoreDecision.automaticConductDeduction();
        String finalComment = scoreDecision == null ? null : scoreDecision.finalComment();
        if (finalStatus != null) {
            writeFinalStatus(resultObject.path(FINAL_RESULT_FIELD).isObject()
                    ? (ObjectNode) resultObject.path(FINAL_RESULT_FIELD)
                    : resultObject.putObject(FINAL_RESULT_FIELD), finalStatus, canonicalScore, conductDeduction, finalComment, scoreDecision);
            writeFinalStatus(copy.path(FINAL_RESULT_FIELD).isObject()
                    ? (ObjectNode) copy.path(FINAL_RESULT_FIELD)
                    : copy.putObject(FINAL_RESULT_FIELD), finalStatus, canonicalScore, conductDeduction, finalComment, scoreDecision);
            ObjectNode summary = resultObject.path("final_summary").isObject()
                    ? (ObjectNode) resultObject.path("final_summary")
                    : resultObject.putObject("final_summary");
            summary.put("backend_compliance_status", finalStatus.name());
            summary.put("schedule_aware_compliance_status", finalStatus.name());
            summary.put("is_compliant", finalStatus == ComplianceStatus.COMPLIANT);
            putNullable(summary, "score", canonicalScore);
            putNullable(summary, "canonical_score", canonicalScore);
            putNullable(summary, "automatic_conduct_deduction", conductDeduction);
            if (finalComment != null) {
                summary.put("vietnamese_comment", finalComment);
            }
            putNullable(copy, "score", canonicalScore);
        }
        return copy;
    }

    private void writeFinalStatus(
            ObjectNode node,
            ComplianceStatus finalStatus,
            Integer canonicalScore,
            Integer conductDeduction,
            String finalComment,
            UniformComplianceScoreService.ScoreDecision scoreDecision
    ) {
        node.put("schedule_aware_compliance_status", finalStatus.name());
        node.put("compliance_status", finalStatus.name());
        node.put("status", finalStatus.name());
        node.put("overall_compliant", finalStatus == ComplianceStatus.COMPLIANT);
        putNullable(node, "canonical_score", canonicalScore);
        putNullable(node, "final_score", canonicalScore);
        putNullable(node, "finalScore", canonicalScore);
        putNullable(node, "automatic_conduct_deduction", conductDeduction);
        putNullable(node, "deducted_points", conductDeduction);
        if (finalComment != null) {
            node.put("final_comment", finalComment);
            node.put("finalComment", finalComment);
        }
        if (scoreDecision != null) {
            node.put("review_issue", scoreDecision.reviewIssue());
            node.set("review_reasons", stringArray(new ArrayList<>(scoreDecision.reviewReasons())));
        }
    }

    public ObjectNode toJson(ScheduleComplianceResult result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("configured", result.configured());
        node.put("applicable", result.applicable());
        node.put("reason", result.reason());
        node.put("className", result.className());
        node.put("dayOfWeek", result.dayOfWeek() == null ? null : result.dayOfWeek().name());
        node.put("dayLabel", result.dayLabel());
        node.put("timeZone", result.timeZone());
        node.put("evaluatedAt", result.evaluatedAt() == null ? null : result.evaluatedAt().toString());
        node.set("requiredComponents", stringArray(result.requiredComponents()));
        node.set("detectedComponents", stringArray(result.detectedComponents()));
        node.set("missingComponents", stringArray(result.missingComponents()));
        if (result.missingRequiredComponentCount() == null) {
            node.putNull("missingRequiredComponentCount");
        } else {
            node.put("missingRequiredComponentCount", result.missingRequiredComponentCount());
        }
        if (result.score() == null) {
            node.putNull("score");
        } else {
            node.put("score", result.score());
        }
        if (result.deductedPoints() == null) {
            node.putNull("deductedPoints");
            node.putNull("automaticConductDeduction");
        } else {
            node.put("deductedPoints", result.deductedPoints());
            node.put("automaticConductDeduction", result.deductedPoints());
        }
        node.put("complianceStatus", result.complianceStatus() == null ? null : result.complianceStatus().name());
        node.set("snapshot", result.snapshot() == null ? NullNode.getInstance() : result.snapshot());
        return node;
    }

    private UniformRequirementScheduleResponse toWeeklyResponse(String className, List<UniformRequirementSchedule> schedules) {
        Map<DayOfWeek, UniformRequirementSchedule> byDay = schedules.stream()
                .collect(Collectors.toMap(UniformRequirementSchedule::getDayOfWeek, Function.identity(), (left, right) -> left));
        Instant updatedAt = schedules.stream()
                .map(UniformRequirementSchedule::getUpdatedAt)
                .filter(value -> value != null)
                .max(Instant::compareTo)
                .orElse(null);
        List<UniformRequirementScheduleDayResponse> days = WEEKDAYS.stream()
                .map(day -> toDayResponse(day, byDay.get(day)))
                .toList();
        return new UniformRequirementScheduleResponse(
                className,
                className,
                scheduleZoneId.getId(),
                componentOptions(),
                days,
                updatedAt
        );
    }

    private UniformRequirementScheduleDayResponse toDayResponse(DayOfWeek day, UniformRequirementSchedule schedule) {
        List<String> required = schedule == null ? List.of() : orderedKeys(schedule.getRequiredComponents());
        return new UniformRequirementScheduleDayResponse(
                day,
                dayLabel(day),
                schedule != null,
                required,
                required.stream()
                        .flatMap(key -> UniformComponent.fromKey(key).stream())
                        .map(component -> new UniformComponentOption(component.key(), component.label()))
                        .toList(),
                schedule == null ? null : schedule.getUpdatedAt()
        );
    }

    private StudentUniformScheduleDayResponse toStudentDayResponse(
            DayOfWeek day,
            UniformRequirementSchedule schedule,
            DayOfWeek today
    ) {
        List<String> required = schedule == null ? List.of() : orderedKeys(schedule.getRequiredComponents());
        return new StudentUniformScheduleDayResponse(
                day,
                dayLabel(day),
                day == today,
                schedule != null,
                required.stream()
                        .flatMap(key -> UniformComponent.fromKey(key).stream())
                        .map(component -> new StudentUniformScheduleComponentResponse(component.key(), component.label()))
                        .toList()
        );
    }

    private Map<DayOfWeek, Set<UniformComponent>> validateWeeklyRequest(UniformRequirementScheduleUpdateRequest request) {
        if (request == null || request.schedules() == null) {
            throw new BadRequestException("Weekly schedule payload is required");
        }
        if (request.schedules().size() != WEEKDAYS.size()) {
            throw new BadRequestException("Weekly schedule must include exactly seven weekdays");
        }
        Map<DayOfWeek, Set<UniformComponent>> byDay = new EnumMap<>(DayOfWeek.class);
        for (UniformRequirementScheduleDayRequest dayRequest : request.schedules()) {
            if (dayRequest == null || dayRequest.dayOfWeek() == null) {
                throw new BadRequestException("Each schedule entry must include dayOfWeek");
            }
            if (byDay.containsKey(dayRequest.dayOfWeek())) {
                throw new BadRequestException("Duplicate weekday in schedule: " + dayRequest.dayOfWeek());
            }
            byDay.put(dayRequest.dayOfWeek(), validateComponents(dayRequest.requiredComponents(), dayRequest.dayOfWeek()));
        }
        if (!byDay.keySet().containsAll(WEEKDAYS)) {
            throw new BadRequestException("Weekly schedule must include Monday through Sunday");
        }
        return byDay;
    }

    private Set<UniformComponent> validateComponents(List<String> componentKeys, DayOfWeek dayOfWeek) {
        List<String> keys = componentKeys == null ? List.of() : componentKeys;
        Set<UniformComponent> components = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                throw new BadRequestException("Invalid blank uniform component in " + dayOfWeek);
            }
            String trimmed = key.trim();
            if (!seen.add(trimmed)) {
                throw new BadRequestException("Duplicate uniform component in " + dayOfWeek + ": " + trimmed);
            }
            UniformComponent component = UniformComponent.fromKey(trimmed)
                    .orElseThrow(() -> new BadRequestException("Invalid uniform component: " + trimmed));
            components.add(component);
        }
        return components;
    }

    private String resolveExistingClassName(String classId) {
        String className = cleanClassName(classId);
        if (className == null) {
            throw new BadRequestException("Class id is required");
        }
        if (studentRepository.countByClassName(className) <= 0) {
            throw new ResourceNotFoundException("Không tìm thấy lớp: " + className);
        }
        return className;
    }

    private ScheduleComplianceResult notApplicable(String reason, String className, DayOfWeek dayOfWeek, Instant evaluatedAt) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("reason", reason);
        snapshot.put("className", className);
        snapshot.put("dayOfWeek", dayOfWeek == null ? null : dayOfWeek.name());
        snapshot.put("dayLabel", dayOfWeek == null ? null : dayLabel(dayOfWeek));
        snapshot.put("timeZone", scheduleZoneId.getId());
        snapshot.put("evaluatedAt", evaluatedAt == null ? null : evaluatedAt.toString());
        return new ScheduleComplianceResult(
                false,
                false,
                reason,
                className,
                dayOfWeek,
                dayOfWeek == null ? null : dayLabel(dayOfWeek),
                scheduleZoneId.getId(),
                evaluatedAt,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                snapshot
        );
    }

    private Set<String> detectedComponentKeys(JsonNode candidate) {
        JsonNode result = candidateResult(candidate);
        JsonNode finalUniqueDetections = result.path("detector_trace").path("final_unique_per_class_detections");
        if (finalUniqueDetections.isArray()) {
            return componentKeysFromArray(finalUniqueDetections);
        }
        Set<String> detected = componentKeysFromArray(result.path("accepted_components"));
        if (!detected.isEmpty()) {
            return detected;
        }
        detected = componentKeysFromArray(candidate == null ? NullNode.getInstance() : candidate.path("accepted_components"));
        if (!detected.isEmpty()) {
            return detected;
        }
        JsonNode requiredItems = result.path("required_items");
        if (requiredItems.isObject()) {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (UniformComponent component : UniformComponent.canonicalValues()) {
                if (requiredItems.path(component.key()).path("present").asBoolean(false)) {
                    keys.add(component.key());
                }
            }
            return keys;
        }
        return new LinkedHashSet<>();
    }

    private Set<String> componentKeysFromArray(JsonNode node) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (node == null || !node.isArray()) {
            return keys;
        }
        for (JsonNode item : node) {
            UniformComponent.fromKey(text(item.path("class_name")))
                    .or(() -> UniformComponent.fromKey(text(item.path("label"))))
                    .ifPresent(component -> keys.add(component.key()));
        }
        return keys;
    }

    private JsonNode candidateResult(JsonNode candidate) {
        if (candidate == null || candidate.isMissingNode() || candidate.isNull()) {
            return NullNode.getInstance();
        }
        JsonNode result = candidate.path("result");
        return result.isMissingNode() || result.isNull() ? candidate : result;
    }

    private ObjectNode candidateResultObject(ObjectNode candidate) {
        JsonNode result = candidate.path("result");
        if (result.isObject()) {
            return (ObjectNode) result;
        }
        return candidate.putObject("result");
    }

    private List<String> orderedKeys(Set<UniformComponent> components) {
        Set<UniformComponent> source = components == null ? Set.of() : components;
        return UniformComponent.canonicalValues().stream()
                .filter(source::contains)
                .map(UniformComponent::key)
                .toList();
    }

    private List<UniformComponentOption> componentOptions() {
        return UniformComponent.canonicalValues().stream()
                .map(component -> new UniformComponentOption(component.key(), component.label()))
                .toList();
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    private void putNullable(ObjectNode node, String fieldName, Integer value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value);
        }
    }

    private String dayLabel(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "Thứ Hai";
            case TUESDAY -> "Thứ Ba";
            case WEDNESDAY -> "Thứ Tư";
            case THURSDAY -> "Thứ Năm";
            case FRIDAY -> "Thứ Sáu";
            case SATURDAY -> "Thứ Bảy";
            case SUNDAY -> "Chủ Nhật";
        };
    }

    private String cleanClassName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private record ClassCount(String className, long studentCount) {
    }
}
