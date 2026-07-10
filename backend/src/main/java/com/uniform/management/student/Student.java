package com.uniform.management.student;

import com.uniform.management.common.enums.Gender;
import com.uniform.management.common.enums.MoralityLevel;
import com.uniform.management.common.model.BaseEntity;
import com.uniform.management.user.UserAccount;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.Period;

@Entity
@Table(
        name = "students",
        indexes = {
                @Index(name = "idx_student_code", columnList = "studentCode", unique = true),
                @Index(name = "idx_student_class", columnList = "className"),
                @Index(name = "idx_student_full_name", columnList = "fullName")
        }
)
public class Student extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String studentCode;

    @Column(nullable = false, unique = true, length = 32)
    private String faceDataId;

    @Column(nullable = false, length = 160)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Gender gender;

    private LocalDate dateOfBirth;

    @Column(length = 64)
    private String className;

    @Column(length = 32)
    private String schoolYear;

    @Column(length = 32)
    private String phone;

    @Column(length = 160)
    private String email;

    @Column(length = 500)
    private String address;

    @Column(nullable = false)
    private int moralityScore = 100;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MoralityLevel moralityLevel = MoralityLevel.GOOD;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private boolean deletionRequested = false;

    @OneToOne(mappedBy = "student", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private UserAccount userAccount;

    public Long getId() {
        return id;
    }

    public String getStudentCode() {
        return studentCode;
    }

    public void setStudentCode(String studentCode) {
        this.studentCode = studentCode;
    }

    public String getFaceDataId() {
        return faceDataId;
    }

    public void setFaceDataId(String faceDataId) {
        this.faceDataId = faceDataId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Gender getGender() {
        return gender;
    }

    public void setGender(Gender gender) {
        this.gender = gender;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public Integer getAge() {
        return ageOn(LocalDate.now());
    }

    public Integer ageOn(LocalDate referenceDate) {
        if (dateOfBirth == null || referenceDate == null || dateOfBirth.isAfter(referenceDate)) {
            return null;
        }
        return Period.between(dateOfBirth, referenceDate).getYears();
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getSchoolYear() {
        return schoolYear;
    }

    public void setSchoolYear(String schoolYear) {
        this.schoolYear = schoolYear;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getMoralityScore() {
        return moralityScore;
    }

    public void setMoralityScore(int moralityScore) {
        this.moralityScore = moralityScore;
    }

    public MoralityLevel getMoralityLevel() {
        return moralityLevel;
    }

    public void setMoralityLevel(MoralityLevel moralityLevel) {
        this.moralityLevel = moralityLevel;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isDeletionRequested() {
        return deletionRequested;
    }

    public void setDeletionRequested(boolean deletionRequested) {
        this.deletionRequested = deletionRequested;
    }

    public UserAccount getUserAccount() {
        return userAccount;
    }

    public void setUserAccount(UserAccount userAccount) {
        this.userAccount = userAccount;
    }
}
