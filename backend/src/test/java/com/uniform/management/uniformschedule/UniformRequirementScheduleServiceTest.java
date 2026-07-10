package com.uniform.management.uniformschedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uniform.management.common.BadRequestException;
import com.uniform.management.common.ResourceNotFoundException;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleDayRequest;
import com.uniform.management.uniformschedule.dto.UniformRequirementScheduleUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class UniformRequirementScheduleServiceTest {

    private static final Instant MONDAY_IN_VIETNAM = Instant.parse("2026-06-29T02:00:00Z");

    @Autowired
    private UniformRequirementScheduleService scheduleService;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void savesSevenDayScheduleAndKeepsClassAndWeekdayIndependent() {
        String suffix = suffix();
        Student classAStudent = saveStudent("SCA" + suffix, "10A-" + suffix);
        saveStudent("SCB" + suffix, "10B-" + suffix);

        scheduleService.updateWeeklySchedule(classAStudent.getClassName(), weekly(Map.of(
                DayOfWeek.MONDAY, List.of(UniformComponent.AO_SO_MI_TRANG.key(), UniformComponent.KHAN_QUANG_DO.key()),
                DayOfWeek.TUESDAY, List.of(UniformComponent.AO_DOAN_THANH_NIEN.key())
        )));
        scheduleService.updateWeeklySchedule("10B-" + suffix, weekly(Map.of(
                DayOfWeek.MONDAY, List.of(UniformComponent.QUAN_TAY_DAI_DEN.key())
        )));

        var classA = scheduleService.getWeeklySchedule(classAStudent.getClassName());
        var classB = scheduleService.getWeeklySchedule("10B-" + suffix);

        assertThat(classA.schedules()).hasSize(7);
        assertThat(classA.schedules().get(0).configured()).isTrue();
        assertThat(classA.schedules().get(0).requiredComponents())
                .containsExactly(UniformComponent.AO_SO_MI_TRANG.key(), UniformComponent.KHAN_QUANG_DO.key());
        assertThat(classA.schedules().get(1).requiredComponents())
                .containsExactly(UniformComponent.AO_DOAN_THANH_NIEN.key());
        assertThat(classB.schedules().get(0).requiredComponents())
                .containsExactly(UniformComponent.QUAN_TAY_DAI_DEN.key());
    }

    @Test
    void validatesExistingClassSevenWeekdaysKnownComponentsAndDuplicates() {
        String className = "11A-" + suffix();
        saveStudent("VAL" + suffix(), className);

        assertThatThrownBy(() -> scheduleService.updateWeeklySchedule("missing-class", weekly(Map.of())))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> scheduleService.updateWeeklySchedule(className, new UniformRequirementScheduleUpdateRequest(
                List.of(new UniformRequirementScheduleDayRequest(DayOfWeek.MONDAY, List.of()))
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("seven");

        assertThatThrownBy(() -> scheduleService.updateWeeklySchedule(className, weekly(Map.of(
                DayOfWeek.MONDAY, List.of(UniformComponent.AO_SO_MI_TRANG.key(), UniformComponent.AO_SO_MI_TRANG.key())
        )))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Duplicate");

        assertThatThrownBy(() -> scheduleService.updateWeeklySchedule(className, weekly(Map.of(
                DayOfWeek.MONDAY, List.of("not_a_uniform_component")
        )))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    void configuredEmptyScheduleIsDifferentFromMissingSchedule() {
        String className = "9E-" + suffix();
        Student student = saveStudent("EMP" + suffix(), className);

        ScheduleComplianceResult missing = scheduleService.evaluate(student, candidateWithFinalUnique(), MONDAY_IN_VIETNAM);
        assertThat(missing.configured()).isFalse();
        assertThat(missing.applicable()).isFalse();
        assertThat(missing.reason()).isEqualTo("weekday_schedule_not_configured");

        scheduleService.updateWeeklySchedule(className, weekly(Map.of()));

        ScheduleComplianceResult configuredEmpty = scheduleService.evaluate(student, candidateWithFinalUnique(), MONDAY_IN_VIETNAM);
        assertThat(configuredEmpty.configured()).isTrue();
        assertThat(configuredEmpty.applicable()).isTrue();
        assertThat(configuredEmpty.requiredComponents()).isEmpty();
        assertThat(configuredEmpty.score()).isEqualTo(100);
        assertThat(configuredEmpty.complianceStatus()).isEqualTo(ComplianceStatus.COMPLIANT);
    }

    @Test
    void scoringUsesFinalUniqueDetectionsAndClampsAtZero() {
        String className = "12C-" + suffix();
        Student student = saveStudent("SCR" + suffix(), className);
        scheduleService.updateWeeklySchedule(className, weekly(Map.of(
                DayOfWeek.MONDAY, UniformComponent.canonicalKeys()
        )));

        ObjectNode candidate = candidateWithFinalUnique(
                UniformComponent.AO_SO_MI_TRANG.key(),
                UniformComponent.QUAN_TAY_DAI_DEN.key(),
                UniformComponent.KHAN_QUANG_DO.key()
        );
        addAccepted(candidate, UniformComponent.canonicalKeys());

        ScheduleComplianceResult result = scheduleService.evaluate(student, candidate, MONDAY_IN_VIETNAM);

        assertThat(result.detectedComponents())
                .containsExactly(UniformComponent.AO_SO_MI_TRANG.key(), UniformComponent.QUAN_TAY_DAI_DEN.key(), UniformComponent.KHAN_QUANG_DO.key());
        assertThat(result.missingComponents()).hasSize(3);
        assertThat(result.score()).isEqualTo(40);
        assertThat(result.deductedPoints()).isEqualTo(30);
        assertThat(result.complianceStatus()).isEqualTo(ComplianceStatus.NON_COMPLIANT);

        ScheduleComplianceResult emptyFinalUnique = scheduleService.evaluate(
                student,
                candidateWithFinalUnique(),
                MONDAY_IN_VIETNAM
        );
        assertThat(emptyFinalUnique.detectedComponents()).isEmpty();
        assertThat(emptyFinalUnique.missingComponents()).hasSize(6);
        assertThat(emptyFinalUnique.score()).isZero();
    }

    @Test
    void scoringRequiresOnlyItemsConfiguredForThatClassSchedule() {
        String className = "8ALT-" + suffix();
        Student student = saveStudent("ALT" + suffix(), className);
        scheduleService.updateWeeklySchedule(className, weekly(Map.of(
                DayOfWeek.MONDAY, List.of(UniformComponent.QUAN_SHORT_TAY_DEN.key())
        )));

        ScheduleComplianceResult result = scheduleService.evaluate(
                student,
                candidateWithFinalUnique(UniformComponent.QUAN_SHORT_TAY_DEN.key()),
                MONDAY_IN_VIETNAM
        );

        assertThat(result.requiredComponents()).containsExactly(UniformComponent.QUAN_SHORT_TAY_DEN.key());
        assertThat(result.missingComponents()).isEmpty();
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.complianceStatus()).isEqualTo(ComplianceStatus.COMPLIANT);
    }

    private Student saveStudent(String code, String className) {
        Student student = new Student();
        student.setStudentCode(code);
        student.setFaceDataId(code);
        student.setFullName("Schedule Student " + code);
        student.setClassName(className);
        return studentRepository.save(student);
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

    private ObjectNode candidateWithFinalUnique(String... componentKeys) {
        ObjectNode candidate = objectMapper.createObjectNode();
        ObjectNode result = candidate.putObject("result");
        ObjectNode trace = result.putObject("detector_trace");
        ArrayNode finalUnique = trace.putArray("final_unique_per_class_detections");
        for (String key : componentKeys) {
            ObjectNode item = finalUnique.addObject();
            item.put("class_name", key);
            item.put("confidence", 0.91);
        }
        result.putArray("accepted_components");
        return candidate;
    }

    private void addAccepted(ObjectNode candidate, List<String> componentKeys) {
        ArrayNode accepted = (ArrayNode) candidate.path("result").path("accepted_components");
        for (String key : componentKeys) {
            ObjectNode item = accepted.addObject();
            item.put("class_name", key);
            item.put("confidence", 0.99);
        }
    }

    private String suffix() {
        String value = Long.toString(System.nanoTime());
        return value.substring(Math.max(0, value.length() - 8));
    }
}
