package com.biddy.searchservice.presentation.controller;

import com.biddy.searchservice.domain.exception.ProductSearchUnavailableException;
import com.biddy.searchservice.presentation.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SearchExceptionHandler {

    @ExceptionHandler(ProductSearchUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleProductSearchUnavailable(ProductSearchUnavailableException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("PRODUCT_VECTOR_SEARCH_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("SEARCH_DEPENDENCY_UNAVAILABLE", e.getMessage()));
    }
}
