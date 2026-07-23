package com.address.address_system.address.coordinate.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.coordinate.admin.CoordinateAdminRepository.CoordinateVersion;
import com.address.address_system.address.coordinate.admin.CoordinateAdminRepository.ActiveCoordinateVersion;
import com.address.address_system.address.coordinate.admin.CoordinateAdminRepository.ReviewCandidate;
import com.address.address_system.address.coordinate.admin.CoordinateAdminService.OperationResult;

import org.junit.jupiter.api.Test;

class CoordinateAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");
    private static final String ACTOR = "address-operator";

    @Test
    void approvesAReviewCandidateAsANewActiveVersion() {
        UUID candidateId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ReviewCandidate candidate = new ReviewCandidate(
                candidateId, targetId, UUID.randomUUID(), 8,
                new BigDecimal("0.7200")
        );
        CoordinateAdminRepository repository = mock(CoordinateAdminRepository.class);
        when(repository.lockReviewCandidate(candidateId))
                .thenReturn(Optional.of(candidate));
        when(repository.lockActiveTarget(targetId)).thenReturn(true);
        when(repository.nextVersionNumber(targetId)).thenReturn(2L);
        CoordinateAdminService service = service(repository);

        OperationResult result = service.approveCandidate(
                candidateId, ACTOR, "현장 진입 위치 확인"
        );

        assertThat(result.versionNumber()).isEqualTo(2L);
        verify(repository).retireActiveCoordinate(targetId, NOW);
        verify(repository).activateCandidate(any(), eq(candidate), eq(2L), eq(NOW));
        verify(repository).recordAudit(
                any(), eq("CANDIDATE_APPROVED"), eq(ACTOR),
                eq("현장 진입 위치 확인"), eq(targetId), eq(candidateId),
                any(), eq(null), eq(null), eq(NOW)
        );
    }

    @Test
    void createsAManualCoordinateWithoutRawDeliveryData() {
        UUID targetId = UUID.randomUUID();
        CoordinateAdminRepository repository = mock(CoordinateAdminRepository.class);
        when(repository.lockActiveTarget(targetId)).thenReturn(true);
        when(repository.nextVersionNumber(targetId)).thenReturn(3L);
        CoordinateAdminService service = service(repository);

        OperationResult result = service.activateManualCoordinate(
                targetId,
                new BigDecimal("37.5665000"),
                new BigDecimal("126.9780000"),
                ACTOR,
                "공동현관 위치 현장 확인"
        );

        assertThat(result.versionNumber()).isEqualTo(3L);
        verify(repository).createManualCoordinate(
                any(), eq(targetId), eq(3L),
                eq(new BigDecimal("37.5665000")),
                eq(new BigDecimal("126.9780000")), eq(NOW)
        );
    }

    @Test
    void restoresAnOldCoordinateAsANewVersion() {
        UUID targetId = UUID.randomUUID();
        UUID sourceCoordinateId = UUID.randomUUID();
        CoordinateAdminRepository repository = mock(CoordinateAdminRepository.class);
        when(repository.lockActiveTarget(targetId)).thenReturn(true);
        when(repository.findVersion(targetId, 1L)).thenReturn(Optional.of(
                new CoordinateVersion(
                        sourceCoordinateId, 1L, "SUPERSEDED",
                        new BigDecimal("0.9100"), 30
                )
        ));
        when(repository.nextVersionNumber(targetId)).thenReturn(4L);
        CoordinateAdminService service = service(repository);

        OperationResult result = service.restoreCoordinate(
                targetId, 1L, ACTOR, "최근 좌표 품질 저하"
        );

        assertThat(result.versionNumber()).isEqualTo(4L);
        verify(repository).restoreCoordinate(
                any(), eq(targetId), eq(4L), eq(sourceCoordinateId), eq(NOW)
        );
    }

    @Test
    void requeuesOnlyThroughTheRepositoryAndAuditsTheCount() {
        UUID targetId = UUID.randomUUID();
        CoordinateAdminRepository repository = mock(CoordinateAdminRepository.class);
        when(repository.lockActiveTarget(targetId)).thenReturn(true);
        when(repository.requeueRetainedSamples(targetId)).thenReturn(17);
        CoordinateAdminService service = service(repository);

        OperationResult result = service.requestReanalysis(
                targetId, ACTOR, "후보 좌표 재검증"
        );

        assertThat(result.affectedSampleCount()).isEqualTo(17);
        verify(repository).recordAudit(
                any(), eq("REANALYSIS_REQUESTED"), eq(ACTOR),
                eq("후보 좌표 재검증"), eq(targetId), eq(null),
                eq(null), eq(null), eq(17), eq(NOW)
        );
    }

    @Test
    void excludesTheActiveCoordinateAndSuspendsAutomaticServing() {
        UUID targetId = UUID.randomUUID();
        UUID coordinateId = UUID.randomUUID();
        CoordinateAdminRepository repository = mock(CoordinateAdminRepository.class);
        when(repository.lockActiveTarget(targetId)).thenReturn(true);
        when(repository.findActiveCoordinateVersion(targetId)).thenReturn(
                Optional.of(new ActiveCoordinateVersion(coordinateId, 3L))
        );
        CoordinateAdminService service = service(repository);

        OperationResult result = service.excludeActiveCoordinate(
                targetId, ACTOR, "잘못된 공동현관 좌표 긴급 중지"
        );

        assertThat(result.coordinateId()).isEqualTo(coordinateId);
        assertThat(result.versionNumber()).isEqualTo(3L);
        verify(repository).excludeActiveCoordinate(targetId, NOW);
        verify(repository).suspendCoordinateServing(targetId);
        verify(repository).recordAudit(
                any(), eq("COORDINATE_EXCLUDED"), eq(ACTOR),
                eq("잘못된 공동현관 좌표 긴급 중지"), eq(targetId), eq(null),
                eq(coordinateId), eq(null), eq(null), eq(NOW)
        );
    }

    private CoordinateAdminService service(CoordinateAdminRepository repository) {
        return new CoordinateAdminService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
