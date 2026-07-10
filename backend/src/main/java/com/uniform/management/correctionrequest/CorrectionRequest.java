package com.uniform.management.correctionrequest;

import com.uniform.management.common.enums.CorrectionStatus;
import com.uniform.management.common.model.BaseEntity;
import com.uniform.management.evaluationhistory.EvaluationHistory;
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
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "correction_requests",
        indexes = {
                @Index(name = "idx_correction_student_history_status", columnList = "student_id,evaluation_history_id,status"),
                @Index(name = "idx_correction_status_created", columnList = "status,created_at")
        }
)
public class CorrectionRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private EvaluationHistory evaluationHistory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Student student;

    /**
     * Audit snapshot captured when the student submits the request. These fields
     * remain nullable so Hibernate ddl-auto=update can evolve databases that
     * already contain correction requests without making old rows unreadable.
     */
    private Integer deductionAtSubmission;

    private Integer requestedDeduction;

    private Integer deductionAfterDecision;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String reason;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String evidenceNote;

    @ManyToOne(fetch = FetchType.LAZY)
    private EvaluationImage evidenceImage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CorrectionStatus status = CorrectionStatus.PENDING;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String adminResponseNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private UserAccount resolvedBy;

    private Instant resolvedAt;

    public Long getId() {
        return id;
    }

    public EvaluationHistory getEvaluationHistory() {
        return evaluationHistory;
    }

    public void setEvaluationHistory(EvaluationHistory evaluationHistory) {
        this.evaluationHistory = evaluationHistory;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }

    public Integer getDeductionAtSubmission() {
        return deductionAtSubmission;
    }

    public void setDeductionAtSubmission(Integer deductionAtSubmission) {
        this.deductionAtSubmission = deductionAtSubmission;
    }

    public Integer getRequestedDeduction() {
        return requestedDeduction;
    }

    public void setRequestedDeduction(Integer requestedDeduction) {
        this.requestedDeduction = requestedDeduction;
    }

    public Integer getDeductionAfterDecision() {
        return deductionAfterDecision;
    }

    public void setDeductionAfterDecision(Integer deductionAfterDecision) {
        this.deductionAfterDecision = deductionAfterDecision;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getEvidenceNote() {
        return evidenceNote;
    }

    public void setEvidenceNote(String evidenceNote) {
        this.evidenceNote = evidenceNote;
    }

    public EvaluationImage getEvidenceImage() {
        return evidenceImage;
    }

    public void setEvidenceImage(EvaluationImage evidenceImage) {
        this.evidenceImage = evidenceImage;
    }

    public CorrectionStatus getStatus() {
        return status;
    }

    public void setStatus(CorrectionStatus status) {
        this.status = status;
    }

    public String getAdminResponseNote() {
        return adminResponseNote;
    }

    public void setAdminResponseNote(String adminResponseNote) {
        this.adminResponseNote = adminResponseNote;
    }

    public UserAccount getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(UserAccount resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
