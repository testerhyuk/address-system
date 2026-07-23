package com.address.address_system.address.coordinate.analysis;

import java.time.Clock;
import java.time.Instant;

import com.address.address_system.address.coordinate.failure.CoordinateProcessingFailureService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CoordinateAnalysisScheduler {

    private final CoordinateAnalysisRepository repository;
    private final CoordinateAnalysisService service;
    private final CoordinateAnalysisProperties properties;
    private final CoordinateProcessingFailureService failureService;
    private final Clock clock;

    public CoordinateAnalysisScheduler(
            CoordinateAnalysisRepository repository,
            CoordinateAnalysisService service,
            CoordinateAnalysisProperties properties,
            CoordinateProcessingFailureService failureService,
            Clock clock
    ) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
        this.failureService = failureService;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${address.coordinate-analysis.schedule-interval:10m}",
            fixedDelayString = "${address.coordinate-analysis.schedule-interval:10m}"
    )
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        Instant sampleFrom = clock.instant().minus(properties.analysisWindow());
        repository.findReadyTargets(
                sampleFrom,
                properties.minSamples(),
                properties.batchSize()
        ).forEach(targetId -> {
            try {
                service.analyze(targetId);
                failureService.resolveAnalysis(targetId);
            }
            catch (RuntimeException exception) {
                failureService.recordAnalysisFailure(targetId, exception);
            }
        });
    }
}
