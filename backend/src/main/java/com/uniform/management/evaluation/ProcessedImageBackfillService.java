package com.uniform.management.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uniform.management.common.enums.EvaluationMethod;
import com.uniform.management.image.EvaluationImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class ProcessedImageBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ProcessedImageBackfillService.class);
    private static final int ERROR_MAX_LENGTH = 1000;

    private final EvaluationRunRepository runRepository;
    private final AiProcessedImageImporter imageImporter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ProcessedImageBackfillService(
            EvaluationRunRepository runRepository,
            AiProcessedImageImporter imageImporter,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.runRepository = runRepository;
        this.imageImporter = imageImporter;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public BackfillSummary repair(Long requestedRunId) {
        List<Long> runIds = requestedRunId != null && requestedRunId > 0
                ? List.of(requestedRunId)
                : runRepository.findIdsMissingProcessedImages();
        int imported = 0;
        int failed = 0;
        int skipped = 0;
        for (Long runId : runIds) {
            BackfillSummary result = repairRun(runId);
            imported += result.imported();
            failed += result.failed();
            skipped += result.skipped();
        }
        return new BackfillSummary(runIds.size(), imported, failed, skipped);
    }

    private BackfillSummary repairRun(Long runId) {
        BackfillSummary result = transactionTemplate.execute(status -> {
            EvaluationRun run = runRepository.findByIdForUpdate(runId).orElse(null);
            if (run == null) {
                log.warn("event=ai_processed_image_backfill_run_missing runId={}", runId);
                return new BackfillSummary(1, 0, 1, 0);
            }
            MethodRepair method1 = repairMethod(run, EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE);
            MethodRepair method2 = repairMethod(run, EvaluationMethod.METHOD_2_YOLOV8_SCHP_FLORENCE);
            return new BackfillSummary(
                    1,
                    method1.imported() + method2.imported(),
                    method1.failed() + method2.failed(),
                    method1.skipped() + method2.skipped()
            );
        });
        return result == null ? new BackfillSummary(1, 0, 1, 0) : result;
    }

    private MethodRepair repairMethod(EvaluationRun run, EvaluationMethod method) {
        EvaluationImage existingImage = method == EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE
                ? run.getMethod1Image()
                : run.getMethod2Image();
        if (existingImage != null) {
            log.info(
                    "event=ai_processed_image_backfill_skipped runId={} method={} imageId={} reason=already_imported",
                    run.getId(), method.getCandidateKey(), existingImage.getId()
            );
            return new MethodRepair(0, 0, 1);
        }

        String sourcePath = method == EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE
                ? run.getMethod1ProcessedImagePath()
                : run.getMethod2ProcessedImagePath();
        String sourceUrl = method == EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE
                ? run.getMethod1ProcessedImageUrl()
                : run.getMethod2ProcessedImageUrl();
        String rawJson = method == EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE
                ? run.getRawMethod1Json()
                : run.getRawMethod2Json();
        if ((sourcePath == null || sourcePath.isBlank()) && (rawJson == null || rawJson.isBlank())) {
            return new MethodRepair(0, 0, 1);
        }

        try {
            JsonNode candidate = candidateWithStoredSources(rawJson, sourcePath, sourceUrl);
            EvaluationImage imported = imageImporter.importProcessedImage(run.getId(), method, candidate);
            String managedUrl = "/api/images/" + imported.getId();
            if (method == EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE) {
                run.setMethod1Image(imported);
                run.setMethod1ProcessedImageUrl(managedUrl);
                run.setMethod1Error(null);
            } else {
                run.setMethod2Image(imported);
                run.setMethod2ProcessedImageUrl(managedUrl);
                run.setMethod2Error(null);
            }
            log.info(
                    "event=ai_processed_image_backfill_imported runId={} method={} imageId={} imageUrl={}",
                    run.getId(), method.getCandidateKey(), imported.getId(), managedUrl
            );
            return new MethodRepair(1, 0, 0);
        } catch (Exception ex) {
            String error = truncate(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            if (method == EvaluationMethod.METHOD_1_GROUNDING_DINO_SCHP_FLORENCE) {
                run.setMethod1Error(error);
            } else {
                run.setMethod2Error(error);
            }
            log.error(
                    "event=ai_processed_image_backfill_failed runId={} method={} reason={}",
                    run.getId(), method.getCandidateKey(), error
            );
            return new MethodRepair(0, 1, 0);
        }
    }

    private JsonNode candidateWithStoredSources(String rawJson, String sourcePath, String sourceUrl) {
        ObjectNode candidate;
        try {
            JsonNode parsed = rawJson == null || rawJson.isBlank() ? null : objectMapper.readTree(rawJson);
            candidate = parsed != null && parsed.isObject()
                    ? (ObjectNode) parsed.deepCopy()
                    : objectMapper.createObjectNode();
        } catch (Exception ignored) {
            candidate = objectMapper.createObjectNode();
        }
        if (sourcePath != null && !sourcePath.isBlank()) {
            candidate.put("processed_image", sourcePath);
        }
        if (sourceUrl != null && !sourceUrl.isBlank() && !sourceUrl.startsWith("/api/images/")) {
            candidate.put("processed_image_url", sourceUrl);
        }
        return candidate;
    }

    private String truncate(String value) {
        return value.length() <= ERROR_MAX_LENGTH ? value : value.substring(0, ERROR_MAX_LENGTH);
    }

    private record MethodRepair(int imported, int failed, int skipped) {
    }

    public record BackfillSummary(int runsScanned, int imported, int failed, int skipped) {
    }
}
