package com.uniform.management.correctionrequest;

import com.uniform.management.common.BadRequestException;
import com.uniform.management.common.ForbiddenActionException;
import com.uniform.management.common.ResourceNotFoundException;
import com.uniform.management.common.enums.CorrectionStatus;
import com.uniform.management.common.enums.ImageType;
import com.uniform.management.correctionrequest.dto.CorrectionRequestResponse;
import com.uniform.management.correctionrequest.dto.ResolveCorrectionRequest;
import com.uniform.management.evaluationhistory.EvaluationHistory;
import com.uniform.management.evaluationhistory.EvaluationHistoryRepository;
import com.uniform.management.image.EvaluationImage;
import com.uniform.management.image.ImageService;
import com.uniform.management.security.SecurityUtils;
import com.uniform.management.student.MoralityService;
import com.uniform.management.student.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@Service
public class CorrectionRequestService {

    private static final int MAX_DEDUCTION = 100;

    private final CorrectionRequestRepository correctionRequestRepository;
    private final EvaluationHistoryRepository evaluationHistoryRepository;
    private final ImageService imageService;
    private final MoralityService moralityService;

    public CorrectionRequestService(
            CorrectionRequestRepository correctionRequestRepository,
            EvaluationHistoryRepository evaluationHistoryRepository,
            ImageService imageService,
            MoralityService moralityService
    ) {
        this.correctionRequestRepository = correctionRequestRepository;
        this.evaluationHistoryRepository = evaluationHistoryRepository;
        this.imageService = imageService;
        this.moralityService = moralityService;
    }

    @Transactional
    public CorrectionRequestResponse create(
            Long evaluationHistoryId,
            Integer requestedDeduction,
            String reason,
            String evidenceNote,
            MultipartFile evidenceImage
    ) {
        Student student = SecurityUtils.currentStudent();
        EvaluationHistory history = evaluationHistoryRepository.findByIdForUpdate(evaluationHistoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy lịch sử đánh giá: " + evaluationHistoryId
                ));
        if (!history.getStudent().getId().equals(student.getId())) {
            throw new ForbiddenActionException("Chỉ được gửi yêu cầu sửa đổi cho lịch sử của chính mình");
        }
        validateDeduction(requestedDeduction);
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Lý do yêu cầu sửa đổi là bắt buộc");
        }
        if (correctionRequestRepository.existsByStudentAndEvaluationHistoryIdAndStatus(
                student,
                evaluationHistoryId,
                CorrectionStatus.PENDING
        )) {
            throw new BadRequestException("Lịch sử đánh giá này đã có một yêu cầu sửa đổi đang chờ duyệt");
        }

        EvaluationImage image = evidenceImage == null || evidenceImage.isEmpty()
                ? null
                : imageService.saveUpload(evidenceImage, ImageType.EVIDENCE_IMAGE);

        CorrectionRequest request = new CorrectionRequest();
        request.setEvaluationHistory(history);
        request.setStudent(student);
        request.setDeductionAtSubmission(history.getDeductedPoints());
        request.setRequestedDeduction(requestedDeduction);
        request.setReason(reason.trim());
        request.setEvidenceNote(normalizeText(evidenceNote));
        request.setEvidenceImage(image);
        request.setStatus(CorrectionStatus.PENDING);
        return CorrectionRequestResponse.from(correctionRequestRepository.save(request));
    }

    @Transactional(readOnly = true)
    public Page<CorrectionRequestResponse> myRequests(Pageable pageable) {
        return correctionRequestRepository
                .findByStudentOrderByCreatedAtDesc(SecurityUtils.currentStudent(), pageable)
                .map(CorrectionRequestResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<CorrectionRequestResponse> all(Pageable pageable) {
        return correctionRequestRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(CorrectionRequestResponse::from);
    }

    @Transactional
    public CorrectionRequestResponse cancel(Long id) {
        CorrectionRequest request = getForUpdate(id);
        if (!request.getStudent().getId().equals(SecurityUtils.currentStudent().getId())) {
            throw new ForbiddenActionException("Chỉ được hủy yêu cầu của chính mình");
        }
        ensurePending(request);
        request.setStatus(CorrectionStatus.CANCELLED);
        return CorrectionRequestResponse.from(correctionRequestRepository.save(request));
    }

    @Transactional
    public CorrectionRequestResponse approve(Long id, ResolveCorrectionRequest resolveRequest) {
        CorrectionRequest request = getForUpdate(id);
        ensurePending(request);
        if (resolveRequest.newDeductedPoints() != null) {
            throw new BadRequestException("Điểm trừ khi đồng ý phải lấy từ đề nghị đã gửi của học sinh");
        }

        EvaluationHistory history = evaluationHistoryRepository.findByIdForUpdate(request.getEvaluationHistory().getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy lịch sử đánh giá: " + request.getEvaluationHistory().getId()
                ));
        Integer requestedDeduction = request.getRequestedDeduction();
        if (requestedDeduction == null) {
            // Requests created before the deduction fields were introduced remain
            // resolvable: approval preserves their current official deduction.
            requestedDeduction = history.getDeductedPoints();
            request.setRequestedDeduction(requestedDeduction);
            if (request.getDeductionAtSubmission() == null) {
                request.setDeductionAtSubmission(history.getDeductedPoints());
            }
        }
        validateDeduction(requestedDeduction);

        int previousDeduction = history.getDeductedPoints();
        if (previousDeduction != requestedDeduction) {
            moralityService.setScore(
                    history.getStudent(),
                    history.getStudent().getMoralityScore() + previousDeduction - requestedDeduction,
                    "Điều chỉnh điểm trừ theo yêu cầu sửa đổi #" + request.getId()
                            + " từ " + previousDeduction + " thành " + requestedDeduction,
                    history,
                    SecurityUtils.currentUser()
            );
            history.setDeductedPoints(requestedDeduction);
        }
        if (resolveRequest.updatedViolationSummary() != null) {
            history.setViolationSummary(resolveRequest.updatedViolationSummary());
        }
        evaluationHistoryRepository.save(history);

        request.setDeductionAfterDecision(requestedDeduction);
        resolve(request, CorrectionStatus.APPROVED, resolveRequest.adminResponseNote());
        return CorrectionRequestResponse.from(correctionRequestRepository.save(request));
    }

    @Transactional
    public CorrectionRequestResponse reject(Long id, ResolveCorrectionRequest resolveRequest) {
        CorrectionRequest request = getForUpdate(id);
        ensurePending(request);
        request.setDeductionAfterDecision(request.getEvaluationHistory().getDeductedPoints());
        resolve(request, CorrectionStatus.REJECTED, resolveRequest.adminResponseNote());
        return CorrectionRequestResponse.from(correctionRequestRepository.save(request));
    }

    private void resolve(CorrectionRequest request, CorrectionStatus status, String note) {
        ensurePending(request);
        request.setStatus(status);
        request.setAdminResponseNote(normalizeText(note));
        request.setResolvedAt(Instant.now());
        request.setResolvedBy(SecurityUtils.currentUser());
    }

    private void ensurePending(CorrectionRequest request) {
        if (request.getStatus() != CorrectionStatus.PENDING) {
            throw new BadRequestException("Yêu cầu sửa đổi đã được xử lý và không thể xử lý lại");
        }
    }

    private CorrectionRequest getForUpdate(Long id) {
        return correctionRequestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu sửa đổi: " + id));
    }

    private void validateDeduction(Integer deductedPoints) {
        if (deductedPoints == null) {
            throw new BadRequestException("Điểm trừ rèn luyện đề nghị là bắt buộc");
        }
        if (deductedPoints < 0 || deductedPoints > MAX_DEDUCTION) {
            throw new BadRequestException("Điểm trừ rèn luyện phải là số nguyên từ 0 đến " + MAX_DEDUCTION);
        }
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
