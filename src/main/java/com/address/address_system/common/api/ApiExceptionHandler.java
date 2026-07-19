package com.address.address_system.common.api;

import java.time.Clock;
import java.time.Instant;

import com.address.address_system.address.search.application.AddressSearchService.InvalidSearchQueryException;
import com.address.address_system.address.search.application.AddressSearchService.DetailedAddressNotAllowedException;
import com.address.address_system.address.search.application.AddressSearchService.UnsupportedSearchParameterException;
import com.address.address_system.address.target.application.DeliveryTargetService.DeliveryTargetException;
import com.address.address_system.address.target.application.DeliveryTargetService.FailureCode;

import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(InvalidSearchQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidSearchQuery(
            InvalidSearchQueryException exception
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_QUERY", exception.getMessage());
    }

    @ExceptionHandler(DetailedAddressNotAllowedException.class)
    public ResponseEntity<ApiErrorResponse> handleDetailedAddress(
            DetailedAddressNotAllowedException exception
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                FailureCode.DETAIL_ADDRESS_NOT_ALLOWED.name(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(UnsupportedSearchParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedSearchParameter(
            UnsupportedSearchParameterException exception
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                FailureCode.INVALID_REQUEST.name(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(DeliveryTargetException.class)
    public ResponseEntity<ApiErrorResponse> handleDeliveryTarget(
            DeliveryTargetException exception
    ) {
        HttpStatus status = switch (exception.getFailureCode()) {
            case ADDRESS_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BUILDING_DONG_NOT_ALLOWED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case INVALID_REQUEST, DETAIL_ADDRESS_NOT_ALLOWED -> HttpStatus.BAD_REQUEST;
        };
        return error(status, exception.getFailureCode().name(), exception.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
        return error(
                HttpStatus.BAD_REQUEST,
                FailureCode.INVALID_REQUEST.name(),
                "요청 값이 올바르지 않습니다"
        );
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message
    ) {
        return ResponseEntity
                .status(status)
                .body(new ApiErrorResponse(code, message, clock.instant()));
    }

    public record ApiErrorResponse(String code, String message, Instant timestamp) {
    }
}
