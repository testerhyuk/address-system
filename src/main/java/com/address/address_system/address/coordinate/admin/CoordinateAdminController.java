package com.address.address_system.address.coordinate.admin;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.address.address_system.address.coordinate.admin.CoordinateAdminService.CandidateResult;
import com.address.address_system.address.coordinate.admin.CoordinateAdminService.CandidateStatus;
import com.address.address_system.address.coordinate.admin.CoordinateAdminService.OperationResult;
import com.address.address_system.address.coordinate.admin.CoordinateAdminService.ProcessingFailureResult;
import com.address.address_system.address.coordinate.admin.CoordinateAdminService.ProcessingFailureStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class CoordinateAdminController {

    private final CoordinateAdminService service;

    public CoordinateAdminController(CoordinateAdminService service) {
        this.service = service;
    }

    @GetMapping("/coordinate-candidates")
    public List<CandidateResult> findCandidates(
            @RequestParam(defaultValue = "REVIEW_REQUIRED") CandidateStatus status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return service.findCandidates(status, limit);
    }

    @GetMapping("/coordinate-processing-failures")
    public List<ProcessingFailureResult> findProcessingFailures(
            @RequestParam(defaultValue = "EXHAUSTED") ProcessingFailureStatus status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return service.findProcessingFailures(status, limit);
    }

    @PostMapping("/coordinate-candidates/{candidateId}/approve")
    public ResponseEntity<OperationResult> approveCandidate(
            @PathVariable UUID candidateId,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.approveCandidate(
                        candidateId, authentication.getName(), request.reason()
                )
        );
    }

    @PostMapping("/coordinate-candidates/{candidateId}/reject")
    public OperationResult rejectCandidate(
            @PathVariable UUID candidateId,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication
    ) {
        return service.rejectCandidate(
                candidateId, authentication.getName(), request.reason()
        );
    }

    @PostMapping("/delivery-targets/{targetId}/coordinates/manual")
    public ResponseEntity<OperationResult> activateManualCoordinate(
            @PathVariable UUID targetId,
            @Valid @RequestBody ManualCoordinateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.activateManualCoordinate(
                        targetId,
                        request.latitude(),
                        request.longitude(),
                        authentication.getName(),
                        request.reason()
                )
        );
    }

    @PostMapping("/delivery-targets/{targetId}/coordinates/{versionNumber}/restore")
    public ResponseEntity<OperationResult> restoreCoordinate(
            @PathVariable UUID targetId,
            @PathVariable @Min(1) long versionNumber,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                service.restoreCoordinate(
                        targetId,
                        versionNumber,
                        authentication.getName(),
                        request.reason()
                )
        );
    }

    @PostMapping("/delivery-targets/{targetId}/coordinates/exclude")
    public OperationResult excludeActiveCoordinate(
            @PathVariable UUID targetId,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication
    ) {
        return service.excludeActiveCoordinate(
                targetId,
                authentication.getName(),
                request.reason()
        );
    }

    @PostMapping("/delivery-targets/{targetId}/reanalyze")
    public ResponseEntity<OperationResult> requestReanalysis(
            @PathVariable UUID targetId,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.accepted().body(
                service.requestReanalysis(
                        targetId, authentication.getName(), request.reason()
                )
        );
    }

    @PostMapping("/delivery-targets/{targetId}/failed-samples/retry")
    public ResponseEntity<OperationResult> retryFailedSamples(
            @PathVariable UUID targetId,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.accepted().body(
                service.retryFailedSamples(
                        targetId, authentication.getName(), request.reason()
                )
        );
    }

    public record DecisionRequest(
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record ManualCoordinateRequest(
            @NotNull
            @DecimalMin("-90")
            @DecimalMax("90")
            @Digits(integer = 2, fraction = 7)
            BigDecimal latitude,
            @NotNull
            @DecimalMin("-180")
            @DecimalMax("180")
            @Digits(integer = 3, fraction = 7)
            BigDecimal longitude,
            @NotBlank @Size(max = 500) String reason
    ) {
    }
}
