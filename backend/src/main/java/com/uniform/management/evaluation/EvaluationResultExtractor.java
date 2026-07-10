package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class EvaluationResultExtractor {

    public JsonNode data(JsonNode root) {
        JsonNode data = root.path("data");
        return data.isMissingNode() || data.isNull() ? root : data;
    }

    public JsonNode uniform(JsonNode root) {
        return data(root).path("uniform");
    }

    public JsonNode uniformPayload(JsonNode root) {
        JsonNode data = data(root);
        JsonNode uniform = data.path("uniform");
        if (uniform.isObject()) {
            return uniform;
        }
        if (data.path("candidates").isArray()) {
            return data;
        }
        if (root.path("candidates").isArray()) {
            return root;
        }
        return uniform;
    }

    public String uniformAiEvaluationId(JsonNode root) {
        return textOrNull(uniformPayload(root).path("evaluation_id"));
    }

    public String preAiImagePath(JsonNode root) {
        JsonNode uniform = uniformPayload(root);
        String path = textOrNull(uniform.path("pre_ai_image"));
        if (path != null) {
            return path;
        }
        return textOrNull(uniform.path("pre_ai_image_metadata").path("path"));
    }

    public String preAiImageUrl(JsonNode root) {
        return textOrNull(uniformPayload(root).path("pre_ai_image_url"));
    }

    public JsonNode candidate(JsonNode root, EvaluationMethod method) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return root;
        }
        if (matchesMethod(method, textOrNull(root.path("method")))) {
            return root;
        }
        JsonNode candidates = uniformPayload(root).path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                String candidateMethod = textOrNull(candidate.path("method"));
                if (matchesMethod(method, candidateMethod)) {
                    return candidate;
                }
            }
        }
        return root.path("__missing_candidate__");
    }

    private boolean matchesMethod(EvaluationMethod method, String value) {
        if (value == null) {
            return false;
        }
        try {
            return EvaluationMethod.fromSelection(value) == method;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public JsonNode candidateResult(JsonNode candidate) {
        JsonNode result = candidate.path("result");
        return result.isMissingNode() || result.isNull() ? candidate : result;
    }

    public String recognizedStudentCode(JsonNode root) {
        JsonNode face = data(root).path("face");
        String identity = textOrNull(face.path("identity"));
        if (identity != null) {
            return identity;
        }
        identity = textOrNull(face.path("student_id"));
        if (identity != null) {
            return identity;
        }
        String best = textOrNull(face.path("best_match").path("student").path("student_id"));
        if (best != null) {
            return best;
        }
        best = textOrNull(face.path("best_match").path("student_id"));
        if (best != null) {
            return best;
        }
        return textOrNull(face.path("student").path("student_id"));
    }

    public ComplianceStatus complianceStatus(JsonNode root) {
        if (!root.path("result").isMissingNode()) {
            return candidateComplianceStatus(root);
        }
        String value = textOrNull(data(root).path("final_decision").path("compliance"));
        if (value == null) {
            value = textOrNull(uniform(root).path("overall").path("compliance"));
        }
        if (value == null) {
            return ComplianceStatus.NEEDS_REVIEW;
        }
        return switch (value) {
            case "compliant" -> ComplianceStatus.COMPLIANT;
            case "partially_compliant" -> ComplianceStatus.PARTIALLY_COMPLIANT;
            case "non_compliant" -> ComplianceStatus.NON_COMPLIANT;
            default -> ComplianceStatus.NEEDS_REVIEW;
        };
    }

    public ComplianceStatus candidateComplianceStatus(JsonNode candidate) {
        JsonNode result = candidateResult(candidate);
        JsonNode summary = result.path("final_summary");
        JsonNode isCompliant = summary.path("is_compliant");
        if (isCompliant.isBoolean()) {
            return isCompliant.asBoolean() ? ComplianceStatus.COMPLIANT : ComplianceStatus.NON_COMPLIANT;
        }
        String value = textOrNull(summary.path("legacy_compliance"));
        if (value == null) {
            value = textOrNull(result.path("overall").path("compliance"));
        }
        if (value == null) {
            return ComplianceStatus.NEEDS_REVIEW;
        }
        return switch (value) {
            case "compliant" -> ComplianceStatus.COMPLIANT;
            case "partially_compliant" -> ComplianceStatus.PARTIALLY_COMPLIANT;
            case "non_compliant" -> ComplianceStatus.NON_COMPLIANT;
            default -> ComplianceStatus.NEEDS_REVIEW;
        };
    }

    public String processedImageUrl(JsonNode root) {
        String candidateUrl = textOrNull(root.path("processed_image_url"));
        if (candidateUrl != null) {
            return candidateUrl;
        }
        candidateUrl = textOrNull(root.path("annotated_image_url"));
        if (candidateUrl != null) {
            return candidateUrl;
        }
        candidateUrl = textOrNull(root.path("image_url"));
        if (candidateUrl != null) {
            return candidateUrl;
        }
        candidateUrl = textOrNull(root.path("final_annotated_image_url"));
        if (candidateUrl != null) {
            return candidateUrl;
        }
        candidateUrl = textOrNull(root.path("result").path("processed_image_url"));
        if (candidateUrl != null) {
            return candidateUrl;
        }
        candidateUrl = textOrNull(root.path("result").path("annotated_image_url"));
        if (candidateUrl != null) {
            return candidateUrl;
        }
        candidateUrl = textOrNull(root.path("result").path("image_url"));
        if (candidateUrl != null) {
            return candidateUrl;
        }
        candidateUrl = textOrNull(root.path("result").path("final_annotated_image_url"));
        if (candidateUrl != null) {
            return candidateUrl;
        }
        JsonNode data = data(root);
        String url = textOrNull(data.path("processed_image_url"));
        if (url != null) {
            return url;
        }
        url = textOrNull(data.path("annotated_image_url"));
        if (url != null) {
            return url;
        }
        url = textOrNull(data.path("image_url"));
        if (url != null) {
            return url;
        }
        url = textOrNull(data.path("final_annotated_image_url"));
        if (url != null) {
            return url;
        }
        JsonNode uniform = uniform(root);
        url = textOrNull(uniform.path("processed_image_url"));
        if (url != null) {
            return url;
        }
        url = textOrNull(uniform.path("annotated_image_url"));
        if (url != null) {
            return url;
        }
        url = textOrNull(uniform.path("image_url"));
        return url != null ? url : textOrNull(uniform.path("final_annotated_image_url"));
    }

    public String processedImagePath(JsonNode root) {
        String path = textOrNull(root.path("processed_image"));
        if (path != null) {
            return path;
        }
        path = textOrNull(root.path("processed_image_path"));
        if (path != null) {
            return path;
        }
        path = textOrNull(root.path("final_annotated_image_path"));
        if (path != null) {
            return path;
        }
        path = textOrNull(root.path("result").path("processed_image"));
        if (path != null) {
            return path;
        }
        path = textOrNull(root.path("result").path("processed_image_path"));
        if (path != null) {
            return path;
        }
        path = textOrNull(root.path("result").path("final_annotated_image_path"));
        if (path != null) {
            return path;
        }
        path = textOrNull(data(root).path("final_annotated_image_path"));
        if (path != null) {
            return path;
        }
        path = textOrNull(uniform(root).path("final_annotated_image_path"));
        return path != null ? path : textOrNull(uniform(root).path("processed_image_path"));
    }

    public boolean requiredPresent(JsonNode root, String key) {
        return uniform(root).path("required_items").path(key).path("present").asBoolean(false);
    }

    public Boolean appearancePass(JsonNode root, String key) {
        String label = textOrNull(uniform(root).path("appearance").path(key).path("label"));
        if (label == null || "uncertain".equals(label)) {
            return null;
        }
        return "pass".equals(label);
    }

    public Set<String> violationTypes(JsonNode root) {
        Set<String> violations = new LinkedHashSet<>();
        if (!requiredPresent(root, "ao_so_mi_trang") && !requiredPresent(root, "ao_doan_thanh_nien")) {
            violations.add("MISSING_SHIRT");
        }
        if (!requiredPresent(root, "quan_tay_dai_den")
                && !requiredPresent(root, "quan_short_tay_den")
                && !requiredPresent(root, "quan_dai_trang")) {
            violations.add("MISSING_BLACK_TROUSERS");
        }
        if (!requiredPresent(root, "khan_quang_do")) {
            violations.add("MISSING_RED_SCARF");
        }
        Boolean tucked = appearancePass(root, "tucked_in");
        if (Boolean.FALSE.equals(tucked)) {
            violations.add("SHIRT_NOT_TUCKED");
        }
        Boolean wrinkled = appearancePass(root, "wrinkled");
        if (Boolean.FALSE.equals(wrinkled)) {
            violations.add("WRINKLED_CLOTHES");
        }
        Boolean dirty = appearancePass(root, "dirty");
        if (Boolean.FALSE.equals(dirty)) {
            violations.add("DIRTY_CLOTHES");
        }
        Boolean torn = appearancePass(root, "torn_or_damaged");
        if (Boolean.FALSE.equals(torn)) {
            violations.add("TORN_CLOTHES");
        }
        return violations;
    }

    public int defaultDeductedPoints(JsonNode root) {
        ComplianceStatus status = complianceStatus(root);
        int points = violationTypes(root).size() * 5;
        if (status == ComplianceStatus.NEEDS_REVIEW && points == 0) {
            return 0;
        }
        return Math.min(20, points);
    }

    public String violationSummary(JsonNode root) {
        Set<String> violations = violationTypes(root);
        if (violations.isEmpty()) {
            return "Không phát hiện vi phạm đồng phục.";
        }
        return String.join(", ", violations);
    }

    public String notes(JsonNode root) {
        List<String> notes = new ArrayList<>();
        JsonNode noteArray = uniform(root).path("notes");
        if (noteArray.isArray()) {
            noteArray.forEach(node -> {
                if (node.isTextual()) {
                    notes.add(node.asText());
                }
            });
        }
        JsonNode reasons = data(root).path("final_decision").path("reasons");
        if (reasons.isArray()) {
            reasons.forEach(node -> {
                if (node.isTextual()) {
                    notes.add(node.asText());
                }
            });
        }
        return String.join("\n", notes);
    }

    public String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }
}
