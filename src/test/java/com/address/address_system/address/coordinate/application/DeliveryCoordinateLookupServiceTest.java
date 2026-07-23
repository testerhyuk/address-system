package com.address.address_system.address.coordinate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.coordinate.model.DeliveryCoordinateLookupResult;
import com.address.address_system.address.coordinate.model.DeliveryCoordinateLookupResult.CoordinateSource;
import com.address.address_system.address.coordinate.persistence.DeliveryCoordinateLookupRepository;
import com.address.address_system.address.coordinate.persistence.DeliveryCoordinateLookupRepository.LookupSource;
import com.address.address_system.address.geocoding.application.InitialCoordinateService;
import com.address.address_system.address.geocoding.application.InitialCoordinateService.Resolution;
import com.address.address_system.address.geocoding.application.InitialCoordinateService.ResolutionStatus;

import org.junit.jupiter.api.Test;

class DeliveryCoordinateLookupServiceTest {

    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID ROAD_ID = UUID.randomUUID();
    private static final UUID COORDINATE_ID = UUID.randomUUID();

    private final DeliveryCoordinateLookupRepository repository =
            mock(DeliveryCoordinateLookupRepository.class);
    private final InitialCoordinateService initialService = mock(InitialCoordinateService.class);
    private final DeliveryCoordinateLookupService service =
            new DeliveryCoordinateLookupService(repository, initialService);

    @Test
    void returnsVerifiedCoordinateFirst() {
        when(repository.findActiveTarget(TARGET_ID)).thenReturn(Optional.of(
                new LookupSource(
                        TARGET_ID, ROAD_ID, COORDINATE_ID, 3L,
                        new BigDecimal("37.5"), new BigDecimal("127.0"),
                        new BigDecimal("0.9500")
                )
        ));

        DeliveryCoordinateLookupResult result = service.find(TARGET_ID);

        assertThat(result.source()).isEqualTo(CoordinateSource.VERIFIED);
        assertThat(result.versionNo()).isEqualTo(3L);
        verifyNoInteractions(initialService);
    }

    @Test
    void fallsBackToInitialBuildingCoordinate() {
        when(repository.findActiveTarget(TARGET_ID)).thenReturn(Optional.of(
                new LookupSource(TARGET_ID, ROAD_ID, null, null, null, null, null)
        ));
        when(initialService.resolve(ROAD_ID)).thenReturn(new Resolution(
                ResolutionStatus.AVAILABLE,
                COORDINATE_ID,
                new BigDecimal("37.5"),
                new BigDecimal("127.0"),
                true
        ));

        DeliveryCoordinateLookupResult result = service.find(TARGET_ID);

        assertThat(result.source()).isEqualTo(CoordinateSource.INITIAL);
        assertThat(result.coordinateId()).isEqualTo(COORDINATE_ID);
    }
}
