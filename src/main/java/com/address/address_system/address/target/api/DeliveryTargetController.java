package com.address.address_system.address.target.api;

import java.util.UUID;

import com.address.address_system.address.target.application.DeliveryTargetService;
import com.address.address_system.address.target.model.DeliveryTargetResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/delivery-targets")
public class DeliveryTargetController {

    private final DeliveryTargetService service;

    public DeliveryTargetController(DeliveryTargetService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DeliveryTargetResult> resolve(
            @Valid @RequestBody ResolveDeliveryTargetRequest request
    ) {
        DeliveryTargetResult result = service.resolve(
                request.roadAddressId(),
                request.buildingDong()
        );
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result);
    }

    public record ResolveDeliveryTargetRequest(
            @NotNull UUID roadAddressId,
            @Size(max = 40) String buildingDong
    ) {
    }
}
