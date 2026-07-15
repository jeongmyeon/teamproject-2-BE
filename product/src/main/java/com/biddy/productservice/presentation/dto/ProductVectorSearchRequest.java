package com.biddy.productservice.presentation.dto;

import java.util.List;

public record ProductVectorSearchRequest(
        List<Double> queryEmbedding,
        int size
) {
}
