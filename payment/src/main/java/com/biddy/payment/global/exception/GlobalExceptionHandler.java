package com.biddy.payment.global.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<com.biddy.payment.global.response.ErrorResponse> handleEntityNotFound(EntityNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(com.biddy.payment.global.response.ErrorResponse.of("NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(PaymentConflictException.class)
    public ResponseEntity<com.biddy.payment.global.response.ErrorResponse> handleConflict(PaymentConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(com.biddy.payment.global.response.ErrorResponse.of("CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(PaymentRedisUnavailableException.class)
    public ResponseEntity<com.biddy.payment.global.response.ErrorResponse> handleRedisUnavailable(PaymentRedisUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(com.biddy.payment.global.response.ErrorResponse.of("SERVICE_UNAVAILABLE", exception.getMessage()));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            IllegalStateException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<com.biddy.payment.global.response.ErrorResponse> handleBadRequest(RuntimeException exception) {
        return ResponseEntity.badRequest()
                .body(com.biddy.payment.global.response.ErrorResponse.of("BAD_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<com.biddy.payment.global.response.ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");

        return ResponseEntity.badRequest()
                .body(com.biddy.payment.global.response.ErrorResponse.of("VALIDATION_ERROR", message));
    }
}
