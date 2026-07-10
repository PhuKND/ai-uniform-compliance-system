package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.student.Student;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class UniformComplianceServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UniformComplianceService service = new UniformComplianceService(objectMapper);

    @Test
    void over16YouthUnionShirtAndBlackTrousersDoesNotRequireRedScarf() {
        UniformComplianceService.UniformComplianceDecision decision = service.evaluate(
                studentAged(17),
                candidateWithAccepted(UniformComplianceService.YOUTH_UNION_SHIRT, UniformComplianceService.BLACK_TROUSERS)
        );

        assertThat(decision.complianceStatus()).isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(decision.missingComponents().size()).isZero();
        assertThat(decision.violationTypes()).doesNotContain("MISSING_RED_SCARF");
        assertThat(decision.over16RuleApplied()).isTrue();
    }

    @Test
    void over16WhiteShirtAndBlackTrousersDoesNotRequireRedScarf() {
        UniformComplianceService.UniformComplianceDecision decision = service.evaluate(
                studentAged(17),
                candidateWithAccepted(UniformComplianceService.WHITE_SHIRT, UniformComplianceService.BLACK_TROUSERS)
        );

        assertThat(decision.complianceStatus()).isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(decision.missingComponents().size()).isZero();
    }

    @Test
    void shortsOrWhiteTrousersSatisfyLowerBodyRequirement() {
        UniformComplianceService.UniformComplianceDecision shortsDecision = service.evaluate(
                studentAged(17),
                candidateWithAccepted(UniformComplianceService.WHITE_SHIRT, UniformComplianceService.BLACK_SHORTS)
        );
        UniformComplianceService.UniformComplianceDecision whiteTrousersDecision = service.evaluate(
                studentAged(17),
                candidateWithAccepted(UniformComplianceService.WHITE_SHIRT, UniformComplianceService.WHITE_TROUSERS)
        );

        assertThat(shortsDecision.complianceStatus()).isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(shortsDecision.missingComponents()).isEmpty();
        assertThat(whiteTrousersDecision.complianceStatus()).isEqualTo(ComplianceStatus.COMPLIANT);
        assertThat(whiteTrousersDecision.missingComponents()).isEmpty();
    }

    @Test
    void over16MissingPantsIsInvalid() {
        UniformComplianceService.UniformComplianceDecision decision = service.evaluate(
                studentAged(17),
                candidateWithAccepted(UniformComplianceService.YOUTH_UNION_SHIRT)
        );

        assertThat(decision.complianceStatus()).isEqualTo(ComplianceStatus.NON_COMPLIANT);
        assertThat(decision.violationTypes()).contains("MISSING_BLACK_TROUSERS");
    }

    @Test
    void age16UsesStandardRuleAndStillRequiresRedScarf() {
        UniformComplianceService.UniformComplianceDecision decision = service.evaluate(
                studentAged(16),
                candidateWithAccepted(UniformComplianceService.YOUTH_UNION_SHIRT, UniformComplianceService.BLACK_TROUSERS)
        );

        assertThat(decision.complianceStatus()).isEqualTo(ComplianceStatus.NON_COMPLIANT);
        assertThat(decision.violationTypes()).contains("MISSING_RED_SCARF");
        assertThat(decision.over16RuleApplied()).isFalse();
    }

    @Test
    void rejectedComponentsDoNotCountAsAccepted() {
        ObjectNode candidate = candidateWithAccepted(UniformComplianceService.YOUTH_UNION_SHIRT);
        ArrayNode rejected = (ArrayNode) candidate.path("result").path("rejected_components");
        ObjectNode rejectedPants = rejected.addObject();
        rejectedPants.put("class_name", UniformComplianceService.BLACK_TROUSERS);
        rejectedPants.put("confidence", 0.92);

        UniformComplianceService.UniformComplianceDecision decision = service.evaluate(studentAged(17), candidate);

        assertThat(decision.acceptedComponentKeys()).doesNotContain(UniformComplianceService.BLACK_TROUSERS);
        assertThat(decision.violationTypes()).contains("MISSING_BLACK_TROUSERS");
    }

    @Test
    void selectedMethodAcceptsNewAiMethodKeysAndOldEnumNames() {
        assertThat(EvaluationMethod.fromSelection("YOLOV8_V2"))
                .isEqualTo(EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);
        assertThat(EvaluationMethod.fromSelection("GROUNDING_DINO_V2"))
                .isEqualTo(EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE);
        assertThat(EvaluationMethod.fromSelection("yolov8_schp_florence2"))
                .isEqualTo(EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);
        assertThat(EvaluationMethod.fromSelection("METHOD_1_GROUNDING_DINO_SCHP_FLORENCE"))
                .isEqualTo(EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE);
        assertThat(EvaluationMethod.fromSelection("LIGHTWEIGHT_GROUNDING_DINO"))
                .isEqualTo(EvaluationMethod.METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO);
        assertThat(EvaluationMethod.fromSelection("LIGHTWEIGHT_YOLOV8_UNIFORM"))
                .isEqualTo(EvaluationMethod.METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM);
    }

    private Student studentAged(int age) {
        Student student = new Student();
        student.setStudentCode("TEST001");
        student.setFaceDataId("TEST001");
        student.setFullName("Test Student");
        student.setDateOfBirth(LocalDate.now().minusYears(age));
        return student;
    }

    private ObjectNode candidateWithAccepted(String... classNames) {
        ObjectNode candidate = objectMapper.createObjectNode();
        candidate.put("method", EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE.getCandidateKey());
        ObjectNode result = candidate.putObject("result");
        ArrayNode accepted = result.putArray("accepted_components");
        for (String className : classNames) {
            ObjectNode item = accepted.addObject();
            item.put("class_name", className);
            item.put("confidence", 0.9);
        }
        result.putArray("missing_components");
        result.putArray("rejected_components");
        ObjectNode tuckIn = result.putObject("tuck_in_assessment");
        tuckIn.put("tucked_in", true);
        ObjectNode appearance = result.putObject("appearance_assessment");
        appearance.put("wrinkled", false);
        appearance.put("dirty", false);
        appearance.put("torn", false);
        ObjectNode summary = result.putObject("final_summary");
        summary.put("score", 90);
        summary.put("is_compliant", true);
        return candidate;
    }
}
