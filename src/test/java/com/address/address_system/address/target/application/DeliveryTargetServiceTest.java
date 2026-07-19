package com.address.address_system.address.target.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.address.address_system.address.target.application.DeliveryTargetService.DeliveryTargetException;
import com.address.address_system.address.target.application.DeliveryTargetService.FailureCode;
import com.address.address_system.address.target.model.DeliveryTargetResult;
import com.address.address_system.address.target.model.DeliveryTargetResult.TargetType;
import com.address.address_system.address.target.persistence.DeliveryTargetRepository;

import org.junit.jupiter.api.Test;

class DeliveryTargetServiceTest {

    private static final UUID ROAD_ADDRESS_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID TARGET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000902");

    private final DeliveryTargetRepository repository = mock(DeliveryTargetRepository.class);
    private final DeliveryTargetService service = new DeliveryTargetService(repository);

    @Test
    void createsBuildingTargetWhenBuildingDongIsMissing() {
        when(repository.lockActiveApartmentStatus(ROAD_ADDRESS_ID))
                .thenReturn(Optional.of("NON_APARTMENT"));
        DeliveryTargetResult expected = new DeliveryTargetResult(
                TARGET_ID,
                ROAD_ADDRESS_ID,
                TargetType.BUILDING,
                null,
                true
        );
        when(repository.saveOrReactivate(
                ROAD_ADDRESS_ID,
                TargetType.BUILDING,
                null,
                null,
                "ADDRESS_SELECTION"
        )).thenReturn(expected);

        assertThat(service.resolve(ROAD_ADDRESS_ID, null)).isEqualTo(expected);
    }

    @Test
    void normalizesAndCreatesApartmentDongTarget() {
        when(repository.lockActiveApartmentStatus(ROAD_ADDRESS_ID))
                .thenReturn(Optional.of("APARTMENT"));
        DeliveryTargetResult expected = new DeliveryTargetResult(
                TARGET_ID,
                ROAD_ADDRESS_ID,
                TargetType.BUILDING_DONG,
                "제101동",
                true
        );
        when(repository.saveOrReactivate(
                ROAD_ADDRESS_ID,
                TargetType.BUILDING_DONG,
                "제101동",
                "제101동",
                "EXTERNAL_DELIVERY"
        )).thenReturn(expected);

        assertThat(service.resolve(ROAD_ADDRESS_ID, " 제 101 동 ")).isEqualTo(expected);
    }

    @Test
    void allowsExternalDongWhenApartmentStatusIsUnknown() {
        when(repository.lockActiveApartmentStatus(ROAD_ADDRESS_ID))
                .thenReturn(Optional.of("UNKNOWN"));

        service.resolve(ROAD_ADDRESS_ID, "A동");

        verify(repository).saveOrReactivate(
                ROAD_ADDRESS_ID,
                TargetType.BUILDING_DONG,
                "A동",
                "a동",
                "EXTERNAL_DELIVERY"
        );
    }

    @Test
    void rejectsDongTargetForNonApartmentAddress() {
        when(repository.lockActiveApartmentStatus(ROAD_ADDRESS_ID))
                .thenReturn(Optional.of("NON_APARTMENT"));

        assertThatThrownBy(() -> service.resolve(ROAD_ADDRESS_ID, "101동"))
                .isInstanceOfSatisfying(DeliveryTargetException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(FailureCode.BUILDING_DONG_NOT_ALLOWED)
                );

        verify(repository, never()).saveOrReactivate(
                ROAD_ADDRESS_ID,
                TargetType.BUILDING_DONG,
                "101동",
                "101동",
                "EXTERNAL_DELIVERY"
        );
    }

    @Test
    void rejectsUnitNumberAndDetailedAddress() {
        when(repository.lockActiveApartmentStatus(ROAD_ADDRESS_ID))
                .thenReturn(Optional.of("APARTMENT"));

        assertThatThrownBy(() -> service.resolve(ROAD_ADDRESS_ID, "101동 1203호"))
                .isInstanceOfSatisfying(DeliveryTargetException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(FailureCode.DETAIL_ADDRESS_NOT_ALLOWED)
                );
    }

    @Test
    void rejectsRetiredOrUnknownAddress() {
        when(repository.lockActiveApartmentStatus(ROAD_ADDRESS_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(ROAD_ADDRESS_ID, null))
                .isInstanceOfSatisfying(DeliveryTargetException.class, exception ->
                        assertThat(exception.getFailureCode())
                                .isEqualTo(FailureCode.ADDRESS_NOT_FOUND)
                );
    }
}
