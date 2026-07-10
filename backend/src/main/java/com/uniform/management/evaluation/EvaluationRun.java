package com.uniform.management.evaluation;

import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.model.BaseEntity;
import com.uniform.management.image.EvaluationImage;
import com.uniform.management.student.Student;
import com.uniform.management.user.UserAccount;
import jakarta.persistence.Column;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

@Entity
@DynamicUpdate
@Table(name = "evaluation_runs")
public class EvaluationRun extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Student requestedStudent;

    @Column(length = 32)
    private String requestedStudentCode;

    @ManyToOne(fetch = FetchType.LAZY)
    private Student recognizedStudent;

    @Column(length = 32)
    private String recognizedStudentCode;

    @Column(length = 180)
    private String uniformAiEvaluationId;

    @Column(length = 1000)
    private String preAiImagePath;

    @Column(length = 1000)
    private String preAiImageUrl;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_image_id")
    private EvaluationImage originalImage;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "method1_image_id")
    private EvaluationImage method1Image;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "method2_image_id")
    private EvaluationImage method2Image;

    @Enumerated(EnumType.STRING)
    @Column(name = "method_1_compliance", length = 32)
    private ComplianceStatus method1Compliance;

    @Enumerated(EnumType.STRING)
    @Column(name = "method_2_compliance", length = 32)
    private ComplianceStatus method2Compliance;

    @Column(name = "method_1_processed_image_path", length = 1000)
    private String method1ProcessedImagePath;

    @Column(name = "method_1_processed_image_url", length = 1000)
    private String method1ProcessedImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "method_1_status", length = 32)
    private EvaluationProcessingStatus method1Status = EvaluationProcessingStatus.PENDING;

    @Column(name = "method_1_error", length = 1000)
    private String method1Error;

    @Column(name = "method_1_completed_at")
    private Instant method1CompletedAt;

    @Column(name = "method_2_processed_image_path", length = 1000)
    private String method2ProcessedImagePath;

    @Column(name = "method_2_processed_image_url", length = 1000)
    private String method2ProcessedImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "method_2_status", length = 32)
    private EvaluationProcessingStatus method2Status = EvaluationProcessingStatus.PENDING;

    @Column(name = "method_2_error", length = 1000)
    private String method2Error;

    @Column(name = "method_2_completed_at")
    private Instant method2CompletedAt;

    @Lob
    @Column(name = "raw_method_1_json", columnDefinition = "LONGTEXT")
    private String rawMethod1Json;

    @Lob
    @Column(name = "method_1_schedule_snapshot_json", columnDefinition = "LONGTEXT")
    private String method1ScheduleSnapshotJson;

    @Lob
    @Column(name = "raw_method_2_json", columnDefinition = "LONGTEXT")
    private String rawMethod2Json;

    @Lob
    @Column(name = "method_2_schedule_snapshot_json", columnDefinition = "LONGTEXT")
    private String method2ScheduleSnapshotJson;

    @Lob
    @Column(name = "raw_ai_response_json", columnDefinition = "LONGTEXT")
    private String rawAiResponseJson;

    @Column(nullable = false)
    private boolean officialSaved = false;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount createdBy;

    public Long getId() {
        return id;
    }

    public Student getRequestedStudent() {
        return requestedStudent;
    }

    public void setRequestedStudent(Student requestedStudent) {
        this.requestedStudent = requestedStudent;
    }

    public String getRequestedStudentCode() {
        return requestedStudentCode;
    }

    public void setRequestedStudentCode(String requestedStudentCode) {
        this.requestedStudentCode = requestedStudentCode;
    }

    public Student getRecognizedStudent() {
        return recognizedStudent;
    }

    public void setRecognizedStudent(Student recognizedStudent) {
        this.recognizedStudent = recognizedStudent;
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

    public EvaluationImage getOriginalImage() {
        return originalImage;
    }

    public void setOriginalImage(EvaluationImage originalImage) {
        this.originalImage = originalImage;
    }

    public EvaluationImage getMethod1Image() {
        return method1Image;
    }

    public void setMethod1Image(EvaluationImage method1Image) {
        this.method1Image = method1Image;
    }

    public EvaluationImage getMethod2Image() {
        return method2Image;
    }

    public void setMethod2Image(EvaluationImage method2Image) {
        this.method2Image = method2Image;
    }

    public ComplianceStatus getMethod1Compliance() {
        return method1Compliance;
    }

    public void setMethod1Compliance(ComplianceStatus method1Compliance) {
        this.method1Compliance = method1Compliance;
    }

    public ComplianceStatus getMethod2Compliance() {
        return method2Compliance;
    }

    public void setMethod2Compliance(ComplianceStatus method2Compliance) {
        this.method2Compliance = method2Compliance;
    }

    public String getMethod1ProcessedImagePath() {
        return method1ProcessedImagePath;
    }

    public void setMethod1ProcessedImagePath(String method1ProcessedImagePath) {
        this.method1ProcessedImagePath = method1ProcessedImagePath;
    }

    public String getMethod1ProcessedImageUrl() {
        return method1ProcessedImageUrl;
    }

    public void setMethod1ProcessedImageUrl(String method1ProcessedImageUrl) {
        this.method1ProcessedImageUrl = method1ProcessedImageUrl;
    }

    public EvaluationProcessingStatus getMethod1Status() {
        return method1Status;
    }

    public void setMethod1Status(EvaluationProcessingStatus method1Status) {
        this.method1Status = method1Status == null ? EvaluationProcessingStatus.PENDING : method1Status;
    }

    public String getMethod1Error() {
        return method1Error;
    }

    public void setMethod1Error(String method1Error) {
        this.method1Error = method1Error;
    }

    public Instant getMethod1CompletedAt() {
        return method1CompletedAt;
    }

    public void setMethod1CompletedAt(Instant method1CompletedAt) {
        this.method1CompletedAt = method1CompletedAt;
    }

    public String getMethod2ProcessedImagePath() {
        return method2ProcessedImagePath;
    }

    public void setMethod2ProcessedImagePath(String method2ProcessedImagePath) {
        this.method2ProcessedImagePath = method2ProcessedImagePath;
    }

    public String getMethod2ProcessedImageUrl() {
        return method2ProcessedImageUrl;
    }

    public void setMethod2ProcessedImageUrl(String method2ProcessedImageUrl) {
        this.method2ProcessedImageUrl = method2ProcessedImageUrl;
    }

    public EvaluationProcessingStatus getMethod2Status() {
        return method2Status;
    }

    public void setMethod2Status(EvaluationProcessingStatus method2Status) {
        this.method2Status = method2Status == null ? EvaluationProcessingStatus.PENDING : method2Status;
    }

    public String getMethod2Error() {
        return method2Error;
    }

    public void setMethod2Error(String method2Error) {
        this.method2Error = method2Error;
    }

    public Instant getMethod2CompletedAt() {
        return method2CompletedAt;
    }

    public void setMethod2CompletedAt(Instant method2CompletedAt) {
        this.method2CompletedAt = method2CompletedAt;
    }

    public String getRawMethod1Json() {
        return rawMethod1Json;
    }

    public void setRawMethod1Json(String rawMethod1Json) {
        this.rawMethod1Json = rawMethod1Json;
    }

    public String getMethod1ScheduleSnapshotJson() {
        return method1ScheduleSnapshotJson;
    }

    public void setMethod1ScheduleSnapshotJson(String method1ScheduleSnapshotJson) {
        this.method1ScheduleSnapshotJson = method1ScheduleSnapshotJson;
    }

    public String getRawMethod2Json() {
        return rawMethod2Json;
    }

    public void setRawMethod2Json(String rawMethod2Json) {
        this.rawMethod2Json = rawMethod2Json;
    }

    public String getMethod2ScheduleSnapshotJson() {
        return method2ScheduleSnapshotJson;
    }

    public void setMethod2ScheduleSnapshotJson(String method2ScheduleSnapshotJson) {
        this.method2ScheduleSnapshotJson = method2ScheduleSnapshotJson;
    }

    public String getRawAiResponseJson() {
        return rawAiResponseJson;
    }

    public void setRawAiResponseJson(String rawAiResponseJson) {
        this.rawAiResponseJson = rawAiResponseJson;
    }

    public boolean isOfficialSaved() {
        return officialSaved;
    }

    public void setOfficialSaved(boolean officialSaved) {
        this.officialSaved = officialSaved;
    }

    public UserAccount getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserAccount createdBy) {
        this.createdBy = createdBy;
    }
}
