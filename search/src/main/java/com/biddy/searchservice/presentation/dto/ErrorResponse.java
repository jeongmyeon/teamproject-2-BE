package com.biddy.searchservice.presentation.dto;

public record ErrorResponse(
        String code,
        String message
) {
}
