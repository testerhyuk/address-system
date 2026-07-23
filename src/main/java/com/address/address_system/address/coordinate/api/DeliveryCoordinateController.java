package com.address.address_system.address.coordinate.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.address.address_system.address.coordinate.application.DeliveryCoordinateService;
import com.address.address_system.address.coordinate.application.DeliveryCoordinateService.Command;
import com.address.address_system.address.coordinate.model.DeliveryCoordinateResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/delivery-coordinates")
public class DeliveryCoordinateController {

    private final DeliveryCoordinateService service;

    public DeliveryCoordinateController(DeliveryCoordinateService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DeliveryCoordinateResult> receive(
            @Valid @RequestBody DeliveryCoordinateRequest request
    ) {
        DeliveryCoordinateResult result = service.receive(new Command(
                request.eventId(),
                request.deliveryTargetId(),
                request.latitude(),
                request.longitude(),
                request.gpsAccuracyMeters(),
                request.completedAt()
        ));
        return ResponseEntity
                .status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(result);
    }

    public record DeliveryCoordinateRequest(
            @NotNull UUID eventId,
            @NotNull UUID deliveryTargetId,
            @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 7)
            BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 7)
            BigDecimal longitude,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2)
            BigDecimal gpsAccuracyMeters,
            @NotNull Instant completedAt
    ) {
    }
}
