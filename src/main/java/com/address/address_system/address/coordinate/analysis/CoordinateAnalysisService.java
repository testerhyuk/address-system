package com.address.address_system.address.coordinate.analysis;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.address.address_system.address.coordinate.analysis.CoordinateAnalysisRepository.AnalysisStats;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CoordinateAnalysisService {

    private final CoordinateAnalysisRepository repository;
    private final CoordinateAnalysisProperties properties;
    private final Clock clock;

    public CoordinateAnalysisService(
            CoordinateAnalysisRepository repository,
            CoordinateAnalysisProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public boolean analyze(UUID targetId) {
        if (!repository.lockActiveTarget(targetId)) {
            return false;
        }
        Instant sampleFrom = clock.instant().minus(properties.analysisWindow());
        if (repository.countPendingSamples(targetId, sampleFrom)
                < properties.minSamples()) {
            return false;
        }

        UUID runId = UUID.randomUUID();
        repository.createRun(runId, targetId, properties, clock.instant());
        repository.clusterSamples(runId, targetId, sampleFrom, properties);
        repository.createCandidates(runId, targetId);
        AnalysisStats stats = repository.readStats(runId);
        repository.complete(runId, stats, clock.instant());
        return true;
    }
}
