package com.uniform.management.common.enums;

import java.util.Arrays;

public enum EvaluationMethod {
    METHOD_1_GROUNDING_DINO_SCHP_FLORENCE(
            "GROUNDING_DINO_V2",
            "Grounding DINO V2 + SCHP + Florence-2",
            1
    ),
    METHOD_2_YOLOV8_SCHP_FLORENCE(
            "YOLOV8_V2",
            "YOLOv8 V2 + SCHP + Florence-2",
            2
    ),
    METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO(
            "LIGHTWEIGHT_GROUNDING_DINO",
            "Pose + InsightFace + Grounding DINO",
            1
    ),
    METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM(
            "LIGHTWEIGHT_YOLOV8_UNIFORM",
            "Pose + InsightFace + YOLOv8 đồng phục",
            2
    );

    private final String candidateKey;
    private final String displayName;
    private final int slot;

    EvaluationMethod(String candidateKey, String displayName, int slot) {
        this.candidateKey = candidateKey;
        this.displayName = displayName;
        this.slot = slot;
    }

    public String getCandidateKey() {
        return candidateKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isMethod1Slot() {
        return slot == 1;
    }

    public boolean isMethod2Slot() {
        return slot == 2;
    }

    public static EvaluationMethod fromSelection(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("selectedMethod is required");
        }
        String normalized = value.trim();
        return Arrays.stream(values())
                .filter(method -> method.name().equalsIgnoreCase(normalized)
                        || method.candidateKey.equalsIgnoreCase(normalized)
                        || aliasMatches(method, normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "selectedMethod must be one of: "
                                + METHOD_1_GROUNDING_DINO_SCHP_FLORENCE.name() + ", "
                                + METHOD_2_YOLOV8_SCHP_FLORENCE.name() + ", "
                                + METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO.name() + ", "
                                + METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM.name() + ", "
                                + METHOD_1_GROUNDING_DINO_SCHP_FLORENCE.candidateKey + ", "
                                + METHOD_2_YOLOV8_SCHP_FLORENCE.candidateKey + ", "
                                + METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO.candidateKey + ", "
                                + METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM.candidateKey
                ));
    }

    private static boolean aliasMatches(EvaluationMethod method, String value) {
        String normalized = value.trim().toLowerCase();
        return switch (method) {
            case METHOD_1_GROUNDING_DINO_SCHP_FLORENCE ->
                    normalized.equals("method_1")
                            || normalized.equals("grounding_dino")
                            || normalized.equals("grounding_dino_v2")
                            || normalized.equals("grounding_dino_schp_florence2");
            case METHOD_2_YOLOV8_SCHP_FLORENCE ->
                    normalized.equals("method_2")
                            || normalized.equals("yolov8")
                            || normalized.equals("yolov8_v2")
                            || normalized.equals("yolov8_schp_florence2");
            case METHOD_1_POSE_INSIGHTFACE_GROUNDING_DINO ->
                    normalized.equals("lightweight_method_1")
                            || normalized.equals("lightweight_grounding_dino")
                            || normalized.equals("grounding_dino_lightweight")
                            || normalized.equals("pose_insightface_grounding_dino")
                            || normalized.equals("no_schp_grounding_dino")
                            || normalized.equals("no_florence_grounding_dino");
            case METHOD_2_POSE_INSIGHTFACE_YOLOV8_UNIFORM ->
                    normalized.equals("lightweight_method_2")
                            || normalized.equals("lightweight_yolov8")
                            || normalized.equals("lightweight_yolov8_uniform")
                            || normalized.equals("yolov8_lightweight")
                            || normalized.equals("pose_insightface_yolov8")
                            || normalized.equals("pose_insightface_yolov8_uniform")
                            || normalized.equals("no_schp_yolov8")
                            || normalized.equals("no_florence_yolov8");
        };
    }
}
