package com.uniform.management.student;

import com.uniform.management.common.model.BaseEntity;
import com.uniform.management.evaluationhistory.EvaluationHistory;
import com.uniform.management.user.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "morality_score_logs")
public class MoralityScoreLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Student student;

    @Column(nullable = false)
    private int previousScore;

    @Column(nullable = false)
    private int newScore;

    @Column(nullable = false)
    private int delta;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    private EvaluationHistory evaluationHistory;

    @ManyToOne(fetch = FetchType.LAZY)
    private UserAccount createdBy;

    public Long getId() {
        return id;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }

    public int getPreviousScore() {
        return previousScore;
    }

    public void setPreviousScore(int previousScore) {
        this.previousScore = previousScore;
    }

    public int getNewScore() {
        return newScore;
    }

    public void setNewScore(int newScore) {
        this.newScore = newScore;
    }

    public int getDelta() {
        return delta;
    }

    public void setDelta(int delta) {
        this.delta = delta;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public EvaluationHistory getEvaluationHistory() {
        return evaluationHistory;
    }

    public void setEvaluationHistory(EvaluationHistory evaluationHistory) {
        this.evaluationHistory = evaluationHistory;
    }

    public UserAccount getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UserAccount createdBy) {
        this.createdBy = createdBy;
    }
}
