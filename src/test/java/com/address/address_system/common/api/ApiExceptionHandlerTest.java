package com.address.address_system.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.address.address_system.address.geocoding.application.InitialCoordinateService.FailureCode;
import com.address.address_system.address.geocoding.application.InitialCoordinateService.ResolutionException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(
            Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void mapsMissingAddressToNotFound() {
        var response = handler.handleCoordinateResolution(
                new ResolutionException(FailureCode.ADDRESS_NOT_FOUND, "not found")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mapsNominatimFailureToServiceUnavailable() {
        var response = handler.handleCoordinateResolution(
                new ResolutionException(
                        FailureCode.NOMINATIM_REQUEST_FAILED,
                        "unavailable"
                )
        );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void mapsCoordinatePersistenceFailureToInternalServerError() {
        var response = handler.handleCoordinateResolution(
                new ResolutionException(
                        FailureCode.COORDINATE_SAVE_FAILED,
                        "save failed"
                )
        );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
