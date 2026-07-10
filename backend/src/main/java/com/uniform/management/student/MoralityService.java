package com.uniform.management.student;

import com.uniform.management.common.enums.MoralityLevel;
import com.uniform.management.evaluationhistory.EvaluationHistory;
import com.uniform.management.user.UserAccount;
import org.springframework.stereotype.Service;

@Service
public class MoralityService {

    private final MoralityScoreLogRepository moralityScoreLogRepository;

    public MoralityService(MoralityScoreLogRepository moralityScoreLogRepository) {
        this.moralityScoreLogRepository = moralityScoreLogRepository;
    }

    public MoralityLevel calculateLevel(int score) {
        if (score >= 80) {
            return MoralityLevel.GOOD;
        }
        if (score >= 65) {
            return MoralityLevel.FAIR;
        }
        if (score >= 50) {
            return MoralityLevel.AVERAGE;
        }
        return MoralityLevel.WEAK;
    }

    public void setScore(Student student, int newScore, String reason, EvaluationHistory history, UserAccount createdBy) {
        int clamped = Math.max(0, Math.min(100, newScore));
        int previous = student.getMoralityScore();
        student.setMoralityScore(clamped);
        student.setMoralityLevel(calculateLevel(clamped));

        MoralityScoreLog log = new MoralityScoreLog();
        log.setStudent(student);
        log.setPreviousScore(previous);
        log.setNewScore(clamped);
        log.setDelta(clamped - previous);
        log.setReason(reason);
        log.setEvaluationHistory(history);
        log.setCreatedBy(createdBy);
        moralityScoreLogRepository.save(log);
    }

    public void deduct(Student student, int points, String reason, EvaluationHistory history, UserAccount createdBy) {
        if (points <= 0) {
            return;
        }
        setScore(student, student.getMoralityScore() - points, reason, history, createdBy);
    }
}
