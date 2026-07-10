package com.uniform.management.statistics;

import com.uniform.management.common.enums.CorrectionStatus;
import com.uniform.management.correctionrequest.CorrectionRequestRepository;
import com.uniform.management.evaluationhistory.EvaluationHistory;
import com.uniform.management.evaluationhistory.EvaluationHistoryRepository;
import com.uniform.management.security.SecurityUtils;
import com.uniform.management.student.MoralityScoreLog;
import com.uniform.management.student.MoralityScoreLogRepository;
import com.uniform.management.student.Student;
import com.uniform.management.student.StudentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatisticsService {

    private final StudentRepository studentRepository;
    private final EvaluationHistoryRepository evaluationHistoryRepository;
    private final CorrectionRequestRepository correctionRequestRepository;
    private final MoralityScoreLogRepository moralityScoreLogRepository;

    public StatisticsService(
            StudentRepository studentRepository,
            EvaluationHistoryRepository evaluationHistoryRepository,
            CorrectionRequestRepository correctionRequestRepository,
            MoralityScoreLogRepository moralityScoreLogRepository
    ) {
        this.studentRepository = studentRepository;
        this.evaluationHistoryRepository = evaluationHistoryRepository;
        this.correctionRequestRepository = correctionRequestRepository;
        this.moralityScoreLogRepository = moralityScoreLogRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalStudents", studentRepository.count());
        stats.put("studentsByClass", toMap(studentRepository.countStudentsByClass()));
        stats.put("studentsByMoralityLevel", toMoralityMap(studentRepository.countStudentsByMoralityLevel()));
        stats.put("averageMoralityScoreByClass", toMap(studentRepository.averageMoralityScoreByClass()));
        stats.put("lowMoralityStudents", studentRepository.findTop10ByMoralityScoreLessThanEqualOrderByMoralityScoreAsc(65).stream()
                .map(this::studentMoralitySummary)
                .toList());
        stats.put("totalEvaluations", evaluationHistoryRepository.count());
        stats.put("evaluationsByStatus", toMap(evaluationHistoryRepository.countEvaluationsByStatus()));
        stats.put("averageCanonicalComplianceScore", evaluationHistoryRepository.averageFinalScore());
        stats.put("scoreDistribution", scoreDistribution(evaluationHistoryRepository.findAll()));
        stats.put("conductDeductionTotal", evaluationHistoryRepository.sumDeductedPoints());
        stats.put("averageCanonicalComplianceScoreByClass", toMap(evaluationHistoryRepository.averageFinalScoreByClass()));
        stats.put("conductDeductionByClass", toMap(evaluationHistoryRepository.sumDeductedPointsByClass()));
        stats.put("totalViolations", evaluationHistoryRepository.countTotalViolations());
        stats.put("violationsByType", toMap(evaluationHistoryRepository.countViolationsByType()));
        stats.put("evaluationsByClass", toMap(evaluationHistoryRepository.countEvaluationsByClass()));
        stats.put("methodComparison", toMap(evaluationHistoryRepository.countEvaluationsByMethod()));
        stats.put("studentsWithMostViolations", evaluationHistoryRepository.studentsWithMostViolations(PageRequest.of(0, 10)).stream()
                .map(this::violationRanking)
                .toList());
        stats.put("correctionRequestStatusCounts", toMap(correctionRequestRepository.countByStatus()));
        return stats;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> studentStatistics() {
        Student student = SecurityUtils.currentStudent();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("studentCode", student.getStudentCode());
        stats.put("moralityScore", student.getMoralityScore());
        stats.put("moralityLevel", student.getMoralityLevel().getVietnameseLabel());
        stats.put("totalEvaluations", evaluationHistoryRepository.countByStudent(student));
        stats.put("evaluationsByStatus", toMap(evaluationHistoryRepository.countEvaluationsByStatusForStudent(student)));
        stats.put("averageCanonicalComplianceScore", evaluationHistoryRepository.averageFinalScoreForStudent(student));
        List<EvaluationHistory> studentHistory = evaluationHistoryRepository.findByStudentOrderByCreatedAtAsc(student);
        stats.put("scoreDistribution", scoreDistribution(studentHistory));
        stats.put("conductDeductionTotal", evaluationHistoryRepository.sumDeductedPointsForStudent(student));
        stats.put("violationsByType", toMap(evaluationHistoryRepository.countViolationsByTypeForStudent(student)));
        stats.put("correctionRequestStatusCounts", toMap(correctionRequestRepository.countByStatusForStudent(student)));
        stats.put("moralityScoreOverTime", moralityScoreLogRepository.findByStudentOrderByCreatedAtAsc(student).stream()
                .map(this::moralityPoint)
                .toList());
        stats.put("deductedPointsOverTime", studentHistory.stream()
                .map(this::deductionPoint)
                .toList());
        stats.put("pendingCorrectionRequests", correctionRequestRepository.countByStatusForStudent(student).stream()
                .filter(row -> row[0] == CorrectionStatus.PENDING)
                .map(row -> row[1])
                .findFirst()
                .orElse(0L));
        return stats;
    }

    private Map<String, Object> toMap(List<Object[]> rows) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            map.put(String.valueOf(row[0]), row[1]);
        }
        return map;
    }

    private Map<String, Object> toMoralityMap(List<Object[]> rows) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Object key = row[0];
            String label = key instanceof com.uniform.management.common.enums.MoralityLevel level
                    ? level.getVietnameseLabel()
                    : String.valueOf(key);
            map.put(label, row[1]);
        }
        return map;
    }

    private Map<String, Object> moralityPoint(MoralityScoreLog log) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("createdAt", log.getCreatedAt());
        point.put("previousScore", log.getPreviousScore());
        point.put("newScore", log.getNewScore());
        point.put("delta", log.getDelta());
        point.put("reason", log.getReason());
        return point;
    }

    private Map<String, Object> deductionPoint(EvaluationHistory history) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("createdAt", history.getCreatedAt());
        point.put("evaluationHistoryId", history.getId());
        point.put("canonicalComplianceScore", history.getFinalScore());
        point.put("deductedPoints", history.getDeductedPoints());
        point.put("violationSummary", history.getViolationSummary());
        return point;
    }

    private Map<String, Object> studentMoralitySummary(Student student) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("studentCode", student.getStudentCode());
        item.put("fullName", student.getFullName());
        item.put("className", student.getClassName());
        item.put("moralityScore", student.getMoralityScore());
        item.put("moralityLevel", student.getMoralityLevel().getVietnameseLabel());
        return item;
    }

    private Map<String, Object> violationRanking(Object[] row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("studentCode", row[0]);
        item.put("studentName", row[1]);
        item.put("violationCount", row[2]);
        return item;
    }

    private Map<String, Object> scoreDistribution(List<EvaluationHistory> histories) {
        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("80_100", 0L);
        distribution.put("65_79", 0L);
        distribution.put("50_64", 0L);
        distribution.put("0_49", 0L);
        distribution.put("unknown", 0L);
        for (EvaluationHistory history : histories) {
            Integer score = history.getFinalScore();
            String key;
            if (score == null) {
                key = "unknown";
            } else if (score >= 80) {
                key = "80_100";
            } else if (score >= 65) {
                key = "65_79";
            } else if (score >= 50) {
                key = "50_64";
            } else {
                key = "0_49";
            }
            distribution.put(key, ((Number) distribution.get(key)).longValue() + 1);
        }
        return distribution;
    }
}
