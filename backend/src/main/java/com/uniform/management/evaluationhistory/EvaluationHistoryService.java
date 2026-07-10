package com.uniform.management.evaluationhistory;

import com.uniform.management.common.BadRequestException;
import com.uniform.management.common.ForbiddenActionException;
import com.uniform.management.common.ResourceNotFoundException;
import com.uniform.management.common.enums.ComplianceStatus;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.evaluationhistory.dto.EvaluationHistoryResponse;
import com.uniform.management.evaluationhistory.dto.EvaluationHistoryUpdateRequest;
import com.uniform.management.security.SecurityUtils;
import com.uniform.management.student.MoralityService;
import com.uniform.management.student.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class EvaluationHistoryService {

    private final EvaluationHistoryRepository evaluationHistoryRepository;
    private final MoralityService moralityService;

    public EvaluationHistoryService(
            EvaluationHistoryRepository evaluationHistoryRepository,
            MoralityService moralityService
    ) {
        this.evaluationHistoryRepository = evaluationHistoryRepository;
        this.moralityService = moralityService;
    }

    @Transactional(readOnly = true)
    public Page<EvaluationHistoryResponse> search(
            String studentCode,
            Long studentId,
            String studentName,
            String className,
            EvaluationMethod method,
            ComplianceStatus status,
            String createdBy,
            Integer minDeducted,
            Integer maxDeducted,
            Instant fromDate,
            Instant toDate,
            Pageable pageable
    ) {
        return evaluationHistoryRepository.search(
                studentCode,
                studentId,
                studentName,
                className,
                method,
                status,
                createdBy,
                minDeducted,
                maxDeducted,
                fromDate,
                toDate,
                pageable
        ).map(EvaluationHistoryResponse::from);
    }

    @Transactional(readOnly = true)
    public EvaluationHistoryResponse getForAdmin(Long id) {
        return EvaluationHistoryResponse.from(get(id));
    }

    @Transactional(readOnly = true)
    public Page<EvaluationHistoryResponse> ownHistory(String violationType, Pageable pageable) {
        Student student = SecurityUtils.currentStudent();
        if (violationType == null || violationType.isBlank()) {
            return evaluationHistoryRepository.findByStudent(student, pageable).map(EvaluationHistoryResponse::from);
        }
        return evaluationHistoryRepository.findOwnByViolationType(student, violationType, pageable)
                .map(EvaluationHistoryResponse::from);
    }

    @Transactional(readOnly = true)
    public EvaluationHistoryResponse getOwn(Long id) {
        EvaluationHistory history = get(id);
        if (!history.getStudent().getId().equals(SecurityUtils.currentStudent().getId())) {
            throw new ForbiddenActionException("Học sinh chỉ được xem lịch sử của chính mình");
        }
        return EvaluationHistoryResponse.from(history);
    }

    @Transactional
    public EvaluationHistoryResponse update(Long id, EvaluationHistoryUpdateRequest request) {
        EvaluationHistory history = get(id);
        if (request.complianceStatus() != null) {
            history.setComplianceStatus(request.complianceStatus());
            history.setOverallCompliant(request.complianceStatus() == ComplianceStatus.COMPLIANT);
        }
        if (request.violationSummary() != null) {
            history.setViolationSummary(request.violationSummary());
        }
        if (request.adminNote() != null) {
            history.setAdminNote(request.adminNote());
        }
        if (request.deductedPoints() != null && request.deductedPoints() != history.getDeductedPoints()) {
            throw new BadRequestException("Điểm trừ rèn luyện tự động được tính từ điểm tuân thủ lịch lớp và không thể chỉnh tay");
        }
        return EvaluationHistoryResponse.from(evaluationHistoryRepository.save(history));
    }

    @Transactional
    public void delete(Long id) {
        EvaluationHistory history = get(id);
        if (history.getDeductedPoints() > 0) {
            moralityService.setScore(
                    history.getStudent(),
                    history.getStudent().getMoralityScore() + history.getDeductedPoints(),
                    "Admin xóa lịch sử đánh giá #" + history.getId() + ", hoàn điểm trừ",
                    history,
                    SecurityUtils.currentUser()
            );
        }
        evaluationHistoryRepository.delete(history);
    }

    private EvaluationHistory get(Long id) {
        return evaluationHistoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lịch sử đánh giá: " + id));
    }
}
