package com.address.address_system.address.coordinate.api;

import java.util.UUID;

import com.address.address_system.address.coordinate.application.DeliveryCoordinateLookupService;
import com.address.address_system.address.coordinate.model.DeliveryCoordinateLookupResult;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/delivery-targets")
public class DeliveryCoordinateLookupController {

    private final DeliveryCoordinateLookupService service;

    public DeliveryCoordinateLookupController(DeliveryCoordinateLookupService service) {
        this.service = service;
    }

    @GetMapping("/{deliveryTargetId}/coordinate")
    public DeliveryCoordinateLookupResult find(
            @PathVariable UUID deliveryTargetId
    ) {
        return service.find(deliveryTargetId);
    }
}
