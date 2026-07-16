package com.biddy.productservice.presentation.dto;

import java.math.BigDecimal;

public record ProductVectorSearchResponse(
        Long productId,
        String name,
        BigDecimal price,
        String status,
        Long sellerId,
        Integer stock,
        String imageUrl,
        Double similarityScore
) {
}
