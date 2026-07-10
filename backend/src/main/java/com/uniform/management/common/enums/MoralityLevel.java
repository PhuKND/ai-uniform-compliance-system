package com.uniform.management.common.enums;

public enum MoralityLevel {
    GOOD("Tốt"),
    FAIR("Khá"),
    AVERAGE("Trung bình"),
    WEAK("Yếu");

    private final String vietnameseLabel;

    MoralityLevel(String vietnameseLabel) {
        this.vietnameseLabel = vietnameseLabel;
    }

    public String getVietnameseLabel() {
        return vietnameseLabel;
    }
}
