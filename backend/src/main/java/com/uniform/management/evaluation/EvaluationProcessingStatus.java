package com.uniform.management.evaluation;

public enum EvaluationProcessingStatus {
    PENDING("pending"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String apiValue;

    EvaluationProcessingStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
