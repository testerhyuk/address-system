package com.address.address_system.address.coordinate.quality;

import com.address.address_system.address.coordinate.failure.CoordinateProcessingFailureService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CoordinateQualityScheduler {

    private final CoordinateQualityRepository repository;
    private final CoordinateQualityService service;
    private final CoordinateQualityProperties properties;
    private final CoordinateProcessingFailureService failureService;

    public CoordinateQualityScheduler(
            CoordinateQualityRepository repository,
            CoordinateQualityService service,
            CoordinateQualityProperties properties,
            CoordinateProcessingFailureService failureService
    ) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
        this.failureService = failureService;
    }

    @Scheduled(
            initialDelayString = "${address.coordinate-quality.schedule-interval:10m}",
            fixedDelayString = "${address.coordinate-quality.schedule-interval:10m}"
    )
    public void run() {
        if (!properties.enabled()) {
            return;
        }
        repository.findGeneratedCandidateIds(properties.batchSize())
                .forEach(candidateId -> {
                    try {
                        service.evaluate(candidateId);
                        failureService.resolveQuality(candidateId);
                    }
                    catch (RuntimeException exception) {
                        failureService.recordQualityFailure(candidateId, exception);
                    }
                });
    }
}
