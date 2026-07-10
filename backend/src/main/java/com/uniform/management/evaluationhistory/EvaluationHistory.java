package com.uniform.management.evaluationhistory;

import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.common.model.BaseEntity;
import com.uniform.management.evaluation.EvaluationRun;
import com.uniform.management.image.EvaluationImage;
import com.uniform.management.student.Student;
import com.uniform.management.user.UserAccount;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "evaluation_history")
public class EvaluationHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Student student;

    @Column(nullable = false, length = 32)
    private String studentCodeSnapshot;

    @Column(nullable = false, length = 160)
    private String studentNameSnapshot;

    @Column(length = 64)
    private String classNameSnapshot;

    private LocalDate dateOfBirthSnapshot;

    private Integer studentAgeAtEvaluation;

    @Column(length = 32)
    private String recognizedStudentCode;

    @Column(length = 180)
    private String uniformAiEvaluationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private EvaluationMethod selectedMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ComplianceStatus complianceStatus;

    private boolean hasWhiteShirt;
    private boolean hasYouthUnionShirt;
    private boolean hasBlackTrousers;
    private boolean hasRedScarf;
    private Boolean shirtTuckedIn;
    private Boolean clothesWrinkled;
    private Boolean clothesDirty;
    private Boolean clothesTorn;
    private boolean overallCompliant;

    @ElementCollection
    @CollectionTable(name = "evaluation_violation_types", joinColumns = @JoinColumn(name = "evaluation_id"))
    @Column(name = "violation_type", length = 80)
    private Set<String> violationTypes = new LinkedHashSet<>();

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String violationSummary;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String aiComment;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String florenceDescription;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String acceptedComponentsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String missingComponentsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String rejectedComponentsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String tuckInAssessmentJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String appearanceAssessmentJson;

    private Integer finalScore;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String finalComment;

    @Column(nullable = false)
    private boolean scheduleConfigured = false;

    @Column(nullable = false)
    private boolean scheduleApplicable = false;

    @Column(length = 80)
    private String scheduleReason;

    @Column(length = 64)
    private String scheduleClassName;

    @Column(length = 16)
    private String scheduleDayOfWeek;

    @Column(length = 40)
    private String scheduleDayLabel;

    @Column(length = 80)
    private String scheduleTimeZone;

    private Instant scheduleEvaluatedAt;

    private Integer scheduleScore;

    private Integer scheduleDeductedPoints;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String scheduleRequiredComponentsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String scheduleDetectedComponentsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String scheduleMissingComponentsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String scheduleSnapshotJson;

    @Lob
    @Column(name = "raw_method_1_json", columnDefinition = "LONGTEXT")
    private String rawMethod1Json;

    @Lob
    @Column(name = "raw_method_2_json", columnDefinition = "LONGTEXT")
    private String rawMethod2Json;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String officialResultJson;

    @Column(nullable = false)
    private int deductedPoints;

    @ManyToOne(fetch = FetchType.LAZY)
    private EvaluationImage originalImage;

    @ManyToOne(fetch = FetchType.LAZY)
    private EvaluationImage processedImage;

    @Column(length = 1000)
    private String preAiImagePath;

    @Column(length = 1000)
    private String preAiImageUrl;

    @Column(length = 1000)
    private String selectedProcessedImagePath;

    @Column(length = 1000)
    private String selectedProcessedImageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    private EvaluationRun comparisonRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount createdBy;

    @Column(length = 1000)
    private String adminNote;

    public Long getId() {
        return id;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }

    public String getStudentCodeSnapshot() {
        return studentCodeSnapshot;
    }

    public void setStudentCodeSnapshot(String studentCodeSnapshot) {
        this.studentCodeSnapshot = studentCodeSnapshot;
    }

    public String getStudentNameSnapshot() {
        return studentNameSnapshot;
    }

    public void setStudentNameSnapshot(String studentNameSnapshot) {
        this.studentNameSnapshot = studentNameSnapshot;
    }

    public String getClassNameSnapshot() {
        return classNameSnapshot;
    }

    public void setClassNameSnapshot(String classNameSnapshot) {
        this.classNameSnapshot = classNameSnapshot;
    }

    public LocalDate getDateOfBirthSnapshot() {
        return dateOfBirthSnapshot;
    }

    public void setDateOfBirthSnapshot(LocalDate dateOfBirthSnapshot) {
        this.dateOfBirthSnapshot = dateOfBirthSnapshot;
    }

    public Integer getStudentAgeAtEvaluation() {
        return studentAgeAtEvaluation;
    }

    public void setStudentAgeAtEvaluation(Integer studentAgeAtEvaluation) {
        this.studentAgeAtEvaluation = studentAgeAtEvaluation;
    }

    public String getRecognizedStudentCode() {
        return recognizedStudentCode;
    }

    public void setRecognizedStudentCode(String recognizedStudentCode) {
        this.recognizedStudentCode = recognizedStudentCode;
    }

    public String getUniformAiEvaluationId() {
        return uniformAiEvaluationId;
    }

    public void setUniformAiEvaluationId(String uniformAiEvaluationId) {
        this.uniformAiEvaluationId = uniformAiEvaluationId;
    }

    public EvaluationMethod getSelectedMethod() {
        return selectedMethod;
    }

    public void setSelectedMethod(EvaluationMethod selectedMethod) {
        this.selectedMethod = selectedMethod;
    }

    public ComplianceStatus getComplianceStatus() {
        return complianceStatus;
    }

    public void setComplianceStatus(ComplianceStatus complianceStatus) {
        this.complianceStatus = complianceStatus;
    }

    public boolean isHasWhiteShirt() {
        return hasWhiteShirt;
    }

    public void setHasWhiteShirt(boolean hasWhiteShirt) {
        this.hasWhiteShirt = hasWhiteShirt;
    }

    public boolean isHasYouthUnionShirt() {
        return hasYouthUnionShirt;
    }

    public void setHasYouthUnionShirt(boolean hasYouthUnionShirt) {
        this.hasYouthUnionShirt = hasYouthUnionShirt;
    }

    public boolean isHasBlackTrousers() {
        return hasBlackTrousers;
    }

    public void setHasBlackTrousers(boolean hasBlackTrousers) {
        this.hasBlackTrousers = hasBlackTrousers;
    }

    public boolean isHasRedScarf() {
        return hasRedScarf;
    }

    public void setHasRedScarf(boolean hasRedScarf) {
        this.hasRedScarf = hasRedScarf;
    }

    public Boolean getShirtTuckedIn() {
        return shirtTuckedIn;
    }

    public void setShirtTuckedIn(Boolean shirtTuckedIn) {
        this.shirtTuckedIn = shirtTuckedIn;
    }

    public Boolean getClothesWrinkled() {
        return clothesWrinkled;
    }

    public void setClothesWrinkled(Boolean clothesWrinkled) {
        this.clothesWrinkled = clothesWrinkled;
    }

    public Boolean getClothesDirty() {
        return clothesDirty;
    }

    public void setClothesDirty(Boolean clothesDirty) {
        this.clothesDirty = clothesDirty;
    }

    public Boolean getClothesTorn() {
        return clothesTorn;
    }

    public void setClothesTorn(Boolean clothesTorn) {
        this.clothesTorn = clothesTorn;
    }

    public boolean isOverallCompliant() {
        return overallCompliant;
    }

    public void setOverallCompliant(boolean overallCompliant) {
        this.overallCompliant = overallCompliant;
    }

    public Set<String> getViolationTypes() {
        return violationTypes;
    }

    public void setViolationTypes(Set<String> violationTypes) {
        this.violationTypes = violationTypes;
    }

    public String getViolationSummary() {
        return violationSummary;
    }

    public void setViolationSummary(String violationSummary) {
        this.violationSummary = violationSummary;
    }

    public String getAiComment() {
        return aiComment;
    }

    public void setAiComment(String aiComment) {
        this.aiComment = aiComment;
    }

    public String getFlorenceDescription() {
        return florenceDescription;
    }

    public void setFlorenceDescription(String florenceDescription) {
        this.florenceDescription = florenceDescription;
    }

    public String getAcceptedComponentsJson() {
        return acceptedComponentsJson;
    }

    public void setAcceptedComponentsJson(String acceptedComponentsJson) {
        this.acceptedComponentsJson = acceptedComponentsJson;
    }

    public String getMissingComponentsJson() {
        return missingComponentsJson;
    }

    public void setMissingComponentsJson(String missingComponentsJson) {
        this.missingComponentsJson = missingComponentsJson;
    }

    public String getRejectedComponentsJson() {
        return rejectedComponentsJson;
    }

    public void setRejectedComponentsJson(String rejectedComponentsJson) {
        this.rejectedComponentsJson = rejectedComponentsJson;
    }

    public String getTuckInAssessmentJson() {
        return tuckInAssessmentJson;
    }

    public void setTuckInAssessmentJson(String tuckInAssessmentJson) {
        this.tuckInAssessmentJson = tuckInAssessmentJson;
    }

    public String getAppearanceAssessmentJson() {
        return appearanceAssessmentJson;
    }

    public void setAppearanceAssessmentJson(String appearanceAssessmentJson) {
        this.appearanceAssessmentJson = appearanceAssessmentJson;
    }

    public Integer getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(Integer finalScore) {
        this.finalScore = finalScore;
    }

    public String getFinalComment() {
        return finalComment;
    }

    public void setFinalComment(String finalComment) {
        this.finalComment = finalComment;
    }

    public boolean isScheduleConfigured() {
        return scheduleConfigured;
    }

    public void setScheduleConfigured(boolean scheduleConfigured) {
        this.scheduleConfigured = scheduleConfigured;
    }

    public boolean isScheduleApplicable() {
        return scheduleApplicable;
    }

    public void setScheduleApplicable(boolean scheduleApplicable) {
        this.scheduleApplicable = scheduleApplicable;
    }

    public String getScheduleReason() {
        return scheduleReason;
    }

    public void setScheduleReason(String scheduleReason) {
        this.scheduleReason = scheduleReason;
    }

    public String getScheduleClassName() {
        return scheduleClassName;
    }

    public void setScheduleClassName(String scheduleClassName) {
        this.scheduleClassName = scheduleClassName;
    }

    public String getScheduleDayOfWeek() {
        return scheduleDayOfWeek;
    }

    public void setScheduleDayOfWeek(String scheduleDayOfWeek) {
        this.scheduleDayOfWeek = scheduleDayOfWeek;
    }

    public String getScheduleDayLabel() {
        return scheduleDayLabel;
    }

    public void setScheduleDayLabel(String scheduleDayLabel) {
        this.scheduleDayLabel = scheduleDayLabel;
    }

    public String getScheduleTimeZone() {
        return scheduleTimeZone;
    }

    public void setScheduleTimeZone(String scheduleTimeZone) {
        this.scheduleTimeZone = scheduleTimeZone;
    }

    public Instant getScheduleEvaluatedAt() {
        return scheduleEvaluatedAt;
    }

    public void setScheduleEvaluatedAt(Instant scheduleEvaluatedAt) {
        this.scheduleEvaluatedAt = scheduleEvaluatedAt;
    }

    public Integer getScheduleScore() {
        return scheduleScore;
    }

    public void setScheduleScore(Integer scheduleScore) {
        this.scheduleScore = scheduleScore;
    }

    public Integer getScheduleDeductedPoints() {
        return scheduleDeductedPoints;
    }

    public void setScheduleDeductedPoints(Integer scheduleDeductedPoints) {
        this.scheduleDeductedPoints = scheduleDeductedPoints;
    }

    public String getScheduleRequiredComponentsJson() {
        return scheduleRequiredComponentsJson;
    }

    public void setScheduleRequiredComponentsJson(String scheduleRequiredComponentsJson) {
        this.scheduleRequiredComponentsJson = scheduleRequiredComponentsJson;
    }

    public String getScheduleDetectedComponentsJson() {
        return scheduleDetectedComponentsJson;
    }

    public void setScheduleDetectedComponentsJson(String scheduleDetectedComponentsJson) {
        this.scheduleDetectedComponentsJson = scheduleDetectedComponentsJson;
    }

    public String getScheduleMissingComponentsJson() {
        return scheduleMissingComponentsJson;
    }

    public void setScheduleMissingComponentsJson(String scheduleMissingComponentsJson) {
        this.scheduleMissingComponentsJson = scheduleMissingComponentsJson;
    }

    public String getScheduleSnapshotJson() {
        return scheduleSnapshotJson;
    }

    public void setScheduleSnapshotJson(String scheduleSnapshotJson) {
        this.scheduleSnapshotJson = scheduleSnapshotJson;
    }

    public String getRawMethod1Json() {
        return rawMethod1Json;
    }

    public void setRawMethod1Json(String rawMethod1Json) {
        this.rawMethod1Json = rawMethod1Json;
    }

    public String getRawMethod2Json() {
        return rawMethod2Json;
    }

    public void setRawMethod2Json(String rawMethod2Json) {
        this.rawMethod2Json = rawMethod2Json;
    }

    public String getOfficialResultJson() {
        return officialResultJson;
    }

    public void setOfficialResultJson(String officialResultJson) {
        this.officialResultJson = officialResultJson;
    }

    public int getDeductedPoints() {
        return deductedPoints;
    }

    public void setDeductedPoints(int deductedPoints) {
        this.deductedPoints = deductedPoints;
    }

    public EvaluationImage getOriginalImage() {
        return originalImage;
    }

    public void setOriginalImage(EvaluationImage originalImage) {
        this.originalImage = originalImage;
    }

    public EvaluationImage getProcessedImage() {
        return processedImage;
    }

    public void setProcessedImage(EvaluationImage processedImage) {
        this.processedImage = processedImage;
    }

    public String getPreAiImagePath() {
        return preAiImagePath;
    }

    public void setPreAiImagePath(String preAiImagePath) {
        this.preAiImagePath = preAiImagePath;
    }

    public String getPreAiImageUrl() {
        return preAiImageUrl;
    }

    public void setPreAiImageUrl(String preAiImageUrl) {
        this.preAiImageUrl = preAiImageUrl;
    }

    public String getSelectedProcessedImagePath() {
        return selectedProcessedImagePath;
    }

    public void setSelectedProcessedImagePath(String selectedProcessedImagePath) {
        this.selectedProcessedImagePath = selectedProcessedImagePath;
    }

    public String getSelectedProcessedImageUrl() {
        return selectedProcessedImageUrl;
    }

    public void setSelectedProcessedImageUrl(String selectedProcessedImageUrl) {
        this.selectedProcessedImageUrl = selectedProcessedImageUrl;
    }

    public EvaluationRun getComparisonRun() {
        return comparisonRun;
    }

    public void setComparisonRun(EvaluationRun comparisonRun) {
        this.comparisonRun = comparisonRun;
    }

    public UserAccount getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserAccount createdBy) {
        this.createdBy = createdBy;
    }

    public String getAdminNote() {
        return adminNote;
    }

    public void setAdminNote(String adminNote) {
        this.adminNote = adminNote;
    }
}
