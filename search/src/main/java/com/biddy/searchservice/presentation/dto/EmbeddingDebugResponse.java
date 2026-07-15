package com.biddy.searchservice.presentation.dto;

import java.util.List;

public record EmbeddingDebugResponse(
        String query,
        int dimension,
        List<Double> embedding
) {
}
