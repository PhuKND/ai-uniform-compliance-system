package com.uniform.management.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "uniform.ai.image-backfill.enabled", havingValue = "true")
public class ProcessedImageBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessedImageBackfillRunner.class);

    private final ProcessedImageBackfillService backfillService;
    private final Long requestedRunId;

    public ProcessedImageBackfillRunner(
            ProcessedImageBackfillService backfillService,
            @Value("${uniform.ai.image-backfill.run-id:0}") Long requestedRunId
    ) {
        this.backfillService = backfillService;
        this.requestedRunId = requestedRunId;
    }

    @Override
    public void run(ApplicationArguments args) {
        ProcessedImageBackfillService.BackfillSummary summary = backfillService.repair(requestedRunId);
        log.info(
                "event=ai_processed_image_backfill_complete requestedRunId={} runsScanned={} imported={} failed={} skipped={}",
                requestedRunId, summary.runsScanned(), summary.imported(), summary.failed(), summary.skipped()
        );
    }
}
