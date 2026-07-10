package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.student.Student;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class UniformComplianceService {

    public static final String WHITE_SHIRT = "ao_so_mi_trang";
    public static final String YOUTH_UNION_SHIRT = "ao_doan_thanh_nien";
    public static final String BLACK_TROUSERS = "quan_tay_dai_den";
    public static final String RED_SCARF = "khan_quang_do";
    public static final String BLACK_SHORTS = "quan_short_tay_den";
    public static final String WHITE_TROUSERS = "quan_dai_trang";

    private static final String MISSING_SHIRT_OPTION = "ao_so_mi_trang_or_ao_doan_thanh_nien";
    private static final String MISSING_LOWER_OPTION = "quan_tay_dai_den_or_quan_short_tay_den_or_quan_dai_trang";

    private static final Map<String, String> LABELS = labels();

    private final ObjectMapper objectMapper;

    public UniformComplianceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public UniformComplianceDecision evaluate(Student student, JsonNode candidate) {
        JsonNode result = candidateResult(candidate);
        ArrayNode acceptedComponents = acceptedComponents(result);
        Set<String> acceptedKeys = acceptedComponentKeys(acceptedComponents);
        if (acceptedKeys.isEmpty()) {
            acceptedKeys = acceptedKeysFromRequiredItems(result);
            acceptedComponents = acceptedComponentsFromKeys(acceptedKeys);
        }

        ArrayNode rejectedComponents = arrayCopy(result.path("rejected_components"));
        JsonNode tuckInAssessment = objectOrNull(result.path("tuck_in_assessment"));
        JsonNode appearanceAssessment = objectOrNull(result.path("appearance_assessment"));

        Integer studentAge = student == null ? null : student.getAge();
        boolean over16 = studentAge != null && studentAge > 16;
        MissingComponents missing = over16
                ? missingForOver16(acceptedKeys)
                : missingForStandardRule(acceptedKeys);

        Boolean shirtTuckedIn = extractTuckedIn(tuckInAssessment, result);
        Boolean wrinkled = extractAppearanceFlag(appearanceAssessment, result, "wrinkled", "wrinkled");
        Boolean dirty = extractAppearanceFlag(appearanceAssessment, result, "dirty", "dirty");
        Boolean torn = extractAppearanceFlag(appearanceAssessment, result, "torn", "torn_or_damaged");

        Set<String> violations = new LinkedHashSet<>(missing.violationTypes());
        if (Boolean.FALSE.equals(shirtTuckedIn)) {
            violations.add("SHIRT_NOT_TUCKED");
        }
        if (Boolean.TRUE.equals(wrinkled)) {
            violations.add("WRINKLED_CLOTHES");
        }
        if (Boolean.TRUE.equals(dirty)) {
            violations.add("DIRTY_CLOTHES");
        }
        if (Boolean.TRUE.equals(torn)) {
            violations.add("TORN_CLOTHES");
        }

        boolean componentsSatisfied = missing.labels().isEmpty();
        ComplianceStatus status;
        if (!componentsSatisfied) {
            status = ComplianceStatus.NON_COMPLIANT;
        } else if (violations.isEmpty()) {
            status = ComplianceStatus.COMPLIANT;
        } else {
            status = ComplianceStatus.PARTIALLY_COMPLIANT;
        }

        int finalScore = adjustedScore(result, status, violations.size());
        String finalComment = finalComment(over16, acceptedKeys, missing.labels(), rejectedComponents.size() > 0,
                shirtTuckedIn, wrinkled, dirty, torn);
        String violationSummary = violationSummary(violations, missing.labels(), rejectedComponents.size() > 0);

        return new UniformComplianceDecision(
                status,
                status == ComplianceStatus.COMPLIANT,
                acceptedKeys,
                acceptedComponents,
                missing.labelsJson(objectMapper),
                rejectedComponents,
                tuckInAssessment,
                appearanceAssessment,
                violations,
                violationSummary,
                finalComment,
                finalScore,
                shirtTuckedIn,
                wrinkled,
                dirty,
                torn,
                over16,
                over16 ? "OVER_16_TWO_VALID_COMBINATIONS" : "STANDARD_WHITE_OR_YOUTH_UNION_WITH_TROUSERS_AND_SCARF"
        );
    }

    public JsonNode withBackendDecision(JsonNode candidate, Student student, UniformComplianceDecision decision) {
        ObjectNode copy = candidate != null && candidate.isObject()
                ? (ObjectNode) candidate.deepCopy()
                : objectMapper.createObjectNode();

        ObjectNode backend = objectMapper.createObjectNode();
        backend.put("student_age", student == null ? null : student.getAge());
        backend.put("age_rule_applied", decision.over16RuleApplied());
        backend.put("component_rule", decision.componentRule());
        backend.put("compliance_status", decision.complianceStatus().name());
        backend.put("overall_compliant", decision.overallCompliant());
        backend.put("final_score", decision.finalScore());
        backend.put("final_comment", decision.finalComment());
        backend.set("accepted_component_keys", stringArray(decision.acceptedComponentKeys()));
        backend.set("accepted_components", decision.acceptedComponents());
        backend.set("missing_components", decision.missingComponents());
        backend.set("rejected_components", decision.rejectedComponents());
        backend.set("tuck_in_assessment", decision.tuckInAssessment());
        backend.set("appearance_assessment", decision.appearanceAssessment());
        backend.set("violation_types", stringArray(decision.violationTypes()));
        backend.put("violation_summary", decision.violationSummary());
        copy.set("backend_final_result", backend);

        JsonNode result = copy.path("result");
        if (result.isObject()) {
            ObjectNode resultObject = (ObjectNode) result;
            resultObject.set("missing_components", decision.missingComponents());
            resultObject.set("accepted_components", decision.acceptedComponents());
            resultObject.set("rejected_components", decision.rejectedComponents());
            ObjectNode finalSummary = resultObject.path("final_summary").isObject()
                    ? (ObjectNode) resultObject.path("final_summary")
                    : resultObject.putObject("final_summary");
            finalSummary.put("is_compliant", decision.overallCompliant());
            finalSummary.put("score", decision.finalScore());
            finalSummary.put("vietnamese_comment", decision.finalComment());
            finalSummary.put("backend_compliance_status", decision.complianceStatus().name());
            finalSummary.put("age_rule_applied", decision.over16RuleApplied());
        }

        return copy;
    }

    private JsonNode candidateResult(JsonNode candidate) {
        if (candidate == null || candidate.isMissingNode() || candidate.isNull()) {
            return NullNode.getInstance();
        }
        JsonNode result = candidate.path("result");
        return result.isMissingNode() || result.isNull() ? candidate : result;
    }

    private ArrayNode acceptedComponents(JsonNode result) {
        ArrayNode accepted = arrayCopy(result.path("accepted_components"));
        if (accepted.size() > 0) {
            return accepted;
        }
        JsonNode legacyAccepted = result.path("accepted_uniform_components");
        return arrayCopy(legacyAccepted);
    }

    private Set<String> acceptedComponentKeys(ArrayNode acceptedComponents) {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode item : acceptedComponents) {
            String key = normalizeComponentKey(text(item.path("class_name")));
            if (key == null) {
                key = normalizeComponentKey(text(item.path("label")));
            }
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private Set<String> acceptedKeysFromRequiredItems(JsonNode result) {
        Set<String> keys = new LinkedHashSet<>();
        JsonNode requiredItems = result.path("required_items");
        if (!requiredItems.isObject()) {
            return keys;
        }
        for (String key : List.of(WHITE_SHIRT, YOUTH_UNION_SHIRT, BLACK_TROUSERS, RED_SCARF, BLACK_SHORTS, WHITE_TROUSERS)) {
            if (requiredItems.path(key).path("present").asBoolean(false)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private ArrayNode acceptedComponentsFromKeys(Set<String> keys) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String key : keys) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("class_name", key);
            item.put("label", label(key));
            array.add(item);
        }
        return array;
    }

    private MissingComponents missingForOver16(Set<String> acceptedKeys) {
        boolean hasWhite = acceptedKeys.contains(WHITE_SHIRT);
        boolean hasYouthUnion = acceptedKeys.contains(YOUTH_UNION_SHIRT);
        boolean hasTrousers = hasAnyLowerBody(acceptedKeys);
        boolean validCase1 = hasYouthUnion && hasTrousers;
        boolean validCase2 = hasWhite && hasTrousers;

        if (validCase1 || validCase2) {
            return new MissingComponents(List.of(), Set.of());
        }

        LinkedHashSet<String> violations = new LinkedHashSet<>();
        ArrayNodeLabels labels = new ArrayNodeLabels();
        if (!hasWhite && !hasYouthUnion) {
            labels.add(label(MISSING_SHIRT_OPTION));
            violations.add("MISSING_SHIRT");
        }
        if (!hasTrousers) {
            labels.add(label(MISSING_LOWER_OPTION));
            violations.add("MISSING_BLACK_TROUSERS");
        }
        return new MissingComponents(labels.values(), violations);
    }

    private MissingComponents missingForStandardRule(Set<String> acceptedKeys) {
        boolean hasWhite = acceptedKeys.contains(WHITE_SHIRT);
        boolean hasYouthUnion = acceptedKeys.contains(YOUTH_UNION_SHIRT);
        boolean hasTrousers = hasAnyLowerBody(acceptedKeys);
        boolean hasScarf = acceptedKeys.contains(RED_SCARF);

        LinkedHashSet<String> violations = new LinkedHashSet<>();
        ArrayNodeLabels labels = new ArrayNodeLabels();
        if (!hasWhite && !hasYouthUnion) {
            labels.add(label(MISSING_SHIRT_OPTION));
            violations.add("MISSING_SHIRT");
        }
        if (!hasTrousers) {
            labels.add(label(MISSING_LOWER_OPTION));
            violations.add("MISSING_BLACK_TROUSERS");
        }
        if (!hasScarf) {
            labels.add(label(RED_SCARF));
            violations.add("MISSING_RED_SCARF");
        }
        return new MissingComponents(labels.values(), violations);
    }

    private Boolean extractTuckedIn(JsonNode tuckInAssessment, JsonNode result) {
        if (tuckInAssessment.isObject()) {
            JsonNode tucked = tuckInAssessment.path("tucked_in");
            if (tucked.isBoolean()) {
                return tucked.asBoolean();
            }
            String status = normalizedText(tuckInAssessment.path("status"));
            if (status.contains("chua so vin") || status.contains("not tucked")) {
                return false;
            }
            if (status.contains("da so vin") || status.contains("tucked")) {
                return true;
            }
        }

        String legacy = normalizedText(result.path("appearance").path("tucked_in").path("label"));
        if ("pass".equals(legacy)) {
            return true;
        }
        if ("fail".equals(legacy)) {
            return false;
        }
        return null;
    }

    private Boolean extractAppearanceFlag(JsonNode appearanceAssessment, JsonNode result, String structuredKey, String legacyKey) {
        if (appearanceAssessment.isObject()) {
            JsonNode value = appearanceAssessment.path(structuredKey);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
        }
        String legacy = normalizedText(result.path("appearance").path(legacyKey).path("label"));
        if ("fail".equals(legacy)) {
            return true;
        }
        if ("pass".equals(legacy)) {
            return false;
        }
        return null;
    }

    private int adjustedScore(JsonNode result, ComplianceStatus status, int violationCount) {
        int score = scoreFromCandidate(result);
        if (status == ComplianceStatus.COMPLIANT) {
            return Math.max(score, 90);
        }
        int adjusted = Math.max(0, score - violationCount * 5);
        if (status == ComplianceStatus.NON_COMPLIANT) {
            return Math.min(adjusted, 70);
        }
        return Math.min(adjusted, 85);
    }

    private int scoreFromCandidate(JsonNode result) {
        JsonNode score = result.path("final_summary").path("score");
        if (score.isNumber()) {
            return clampScore(score.asInt());
        }
        JsonNode legacyScore = result.path("overall").path("score");
        if (legacyScore.isNumber()) {
            double value = legacyScore.asDouble();
            return clampScore(value <= 1.0 ? (int) Math.round(value * 100) : (int) Math.round(value));
        }
        return 100;
    }

    private int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String finalComment(
            boolean over16,
            Set<String> acceptedKeys,
            List<String> missingLabels,
            boolean hasRejectedComponents,
            Boolean shirtTuckedIn,
            Boolean wrinkled,
            Boolean dirty,
            Boolean torn
    ) {
        List<String> comments = new java.util.ArrayList<>();
        if (over16
                && acceptedKeys.contains(YOUTH_UNION_SHIRT)
                && acceptedKeys.contains(BLACK_TROUSERS)) {
            comments.add("H\u1ecdc sinh tr\u00ean 16 tu\u1ed5i, m\u1eb7c \u00e1o \u0110o\u00e0n Thanh ni\u00ean v\u00e0 qu\u1ea7n t\u00e2y d\u00e0i \u0111en n\u00ean \u0111\u01b0\u1ee3c \u0111\u00e1nh gi\u00e1 l\u00e0 \u0111\u1ee7 th\u00e0nh ph\u1ea7n \u0111\u1ed3ng ph\u1ee5c.");
        } else if (over16
                && acceptedKeys.contains(WHITE_SHIRT)
                && acceptedKeys.contains(BLACK_TROUSERS)) {
            comments.add("H\u1ecdc sinh tr\u00ean 16 tu\u1ed5i, m\u1eb7c \u00e1o s\u01a1 mi tr\u1eafng v\u00e0 qu\u1ea7n t\u00e2y d\u00e0i \u0111en n\u00ean \u0111\u01b0\u1ee3c \u0111\u00e1nh gi\u00e1 l\u00e0 \u0111\u1ee7 th\u00e0nh ph\u1ea7n \u0111\u1ed3ng ph\u1ee5c.");
        } else if (missingLabels.isEmpty()) {
            comments.add("H\u1ecdc sinh m\u1eb7c \u0111\u1ee7 c\u00e1c th\u00e0nh ph\u1ea7n \u0111\u1ed3ng ph\u1ee5c theo quy \u0111\u1ecbnh.");
        } else {
            comments.add("H\u1ecdc sinh ch\u01b0a \u0111\u1ee7 \u0111i\u1ec1u ki\u1ec7n th\u00e0nh ph\u1ea7n \u0111\u1ed3ng ph\u1ee5c theo quy \u0111\u1ecbnh.");
            comments.add("Thi\u1ebfu " + String.join(", ", missingLabels) + ".");
        }

        if (hasRejectedComponents) {
            comments.add("M\u1ed9t s\u1ed1 th\u00e0nh ph\u1ea7n b\u1ecb t\u1eeb ch\u1ed1i v\u00ec n\u1eb1m ngo\u00e0i c\u01a1 th\u1ec3 h\u1ecdc sinh n\u00ean kh\u00f4ng \u0111\u01b0\u1ee3c t\u00ednh.");
        }
        if (Boolean.FALSE.equals(shirtTuckedIn)) {
            comments.add("\u00c1o c\u00f3 d\u1ea5u hi\u1ec7u ch\u01b0a s\u01a1 vin.");
        }
        if (Boolean.TRUE.equals(wrinkled) || Boolean.TRUE.equals(dirty) || Boolean.TRUE.equals(torn)) {
            comments.add("Trang ph\u1ee5c c\u00f3 d\u1ea5u hi\u1ec7u nh\u0103n/b\u1ea9n/r\u00e1ch, c\u1ea7n ki\u1ec3m tra l\u1ea1i.");
        }
        return String.join(" ", comments);
    }

    private String violationSummary(Set<String> violations, List<String> missingLabels, boolean hasRejectedComponents) {
        if (violations.isEmpty() && !hasRejectedComponents) {
            return "Kh\u00f4ng ph\u00e1t hi\u1ec7n vi ph\u1ea1m \u0111\u1ed3ng ph\u1ee5c.";
        }
        List<String> parts = new java.util.ArrayList<>();
        if (!missingLabels.isEmpty()) {
            parts.add("Thi\u1ebfu " + String.join(", ", missingLabels));
        }
        violations.stream()
                .filter(value -> !value.startsWith("MISSING_"))
                .forEach(parts::add);
        if (hasRejectedComponents) {
            parts.add("C\u00f3 th\u00e0nh ph\u1ea7n b\u1ecb t\u1eeb ch\u1ed1i v\u00ec n\u1eb1m ngo\u00e0i c\u01a1 th\u1ec3 h\u1ecdc sinh");
        }
        return String.join("; ", parts);
    }

    private String normalizeComponentKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String raw = value.trim();
        if (LABELS.containsKey(raw)) {
            return raw;
        }
        String normalized = normalizedText(raw);
        for (Map.Entry<String, String> entry : LABELS.entrySet()) {
            if (normalized.equals(normalizedText(entry.getValue()))) {
                return entry.getKey();
            }
        }
        return switch (normalized) {
            case "ao so mi trang", "white shirt", "white school shirt" -> WHITE_SHIRT;
            case "ao doan thanh nien", "youth union shirt", "blue youth union shirt" -> YOUTH_UNION_SHIRT;
            case "quan tay dai den", "black trousers", "long black trousers" -> BLACK_TROUSERS;
            case "khan quang do", "red scarf" -> RED_SCARF;
            case "quan short den", "quan short tay den", "black shorts", "black school shorts" -> BLACK_SHORTS;
            case "quan dai trang", "white trousers", "white long trousers" -> WHITE_TROUSERS;
            default -> null;
        };
    }

    private boolean hasAnyLowerBody(Set<String> acceptedKeys) {
        return acceptedKeys.contains(BLACK_TROUSERS)
                || acceptedKeys.contains(BLACK_SHORTS)
                || acceptedKeys.contains(WHITE_TROUSERS);
    }

    private String normalizedText(JsonNode node) {
        return normalizedText(text(node));
    }

    private String normalizedText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace("\u0110", "D")
                .replace("\u0111", "d")
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private JsonNode objectOrNull(JsonNode node) {
        return node != null && node.isObject() ? node.deepCopy() : NullNode.getInstance();
    }

    private ArrayNode arrayCopy(JsonNode node) {
        if (node != null && node.isArray()) {
            return (ArrayNode) node.deepCopy();
        }
        return objectMapper.createArrayNode();
    }

    private ArrayNode stringArray(Set<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private String label(String key) {
        return LABELS.getOrDefault(key, key);
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(WHITE_SHIRT, "\u00e1o s\u01a1 mi tr\u1eafng");
        labels.put(YOUTH_UNION_SHIRT, "\u00e1o \u0111o\u00e0n thanh ni\u00ean");
        labels.put(BLACK_TROUSERS, "qu\u1ea7n t\u00e2y d\u00e0i \u0111en");
        labels.put(RED_SCARF, "kh\u0103n qu\u00e0ng \u0111\u1ecf");
        labels.put(BLACK_SHORTS, "qu\u1ea7n short \u0111en");
        labels.put(WHITE_TROUSERS, "qu\u1ea7n d\u00e0i tr\u1eafng");
        labels.put(MISSING_SHIRT_OPTION, "\u00e1o s\u01a1 mi tr\u1eafng ho\u1eb7c \u00e1o \u0111o\u00e0n thanh ni\u00ean");
        labels.put(MISSING_LOWER_OPTION, "qu\u1ea7n t\u00e2y d\u00e0i \u0111en, qu\u1ea7n short \u0111en ho\u1eb7c qu\u1ea7n d\u00e0i tr\u1eafng");
        return Map.copyOf(labels);
    }

    private record MissingComponents(List<String> labels, Set<String> violationTypes) {
        JsonNode labelsJson(ObjectMapper mapper) {
            ArrayNode array = mapper.createArrayNode();
            labels.forEach(array::add);
            return array;
        }
    }

    private static final class ArrayNodeLabels {
        private final List<String> values = new java.util.ArrayList<>();

        void add(String value) {
            values.add(value);
        }

        List<String> values() {
            return values;
        }
    }

    public record UniformComplianceDecision(
            ComplianceStatus complianceStatus,
            boolean overallCompliant,
            Set<String> acceptedComponentKeys,
            JsonNode acceptedComponents,
            JsonNode missingComponents,
            JsonNode rejectedComponents,
            JsonNode tuckInAssessment,
            JsonNode appearanceAssessment,
            Set<String> violationTypes,
            String violationSummary,
            String finalComment,
            int finalScore,
            Boolean shirtTuckedIn,
            Boolean clothesWrinkled,
            Boolean clothesDirty,
            Boolean clothesTorn,
            boolean over16RuleApplied,
            String componentRule
    ) {
        public int defaultDeductedPoints() {
            if (complianceStatus == ComplianceStatus.COMPLIANT) {
                return 0;
            }
            return Math.min(20, Math.max(0, violationTypes.size()) * 5);
        }
    }
}
