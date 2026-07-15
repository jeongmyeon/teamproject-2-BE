package com.biddy.searchservice.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record SearchRequest(
        @NotBlank String query,
        @Positive Integer size
) {
}
