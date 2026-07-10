package com.uniform.management.facedata;

import com.uniform.management.common.model.BaseEntity;
import com.uniform.management.student.Student;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "face_data_records")
public class FaceDataRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(nullable = false, length = 32)
    private String studentCodeSnapshot;

    @Column(nullable = false, length = 32)
    private String faceDataId;

    @Column(nullable = false)
    private boolean enrolled;

    @Column(nullable = false)
    private int sampleCount;

    private Instant lastSyncedAt;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String aiResponseJson;

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

    public String getFaceDataId() {
        return faceDataId;
    }

    public void setFaceDataId(String faceDataId) {
        this.faceDataId = faceDataId;
    }

    public boolean isEnrolled() {
        return enrolled;
    }

    public void setEnrolled(boolean enrolled) {
        this.enrolled = enrolled;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public String getAiResponseJson() {
        return aiResponseJson;
    }

    public void setAiResponseJson(String aiResponseJson) {
        this.aiResponseJson = aiResponseJson;
    }
}
