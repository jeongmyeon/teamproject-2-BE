package com.biddy.searchservice.presentation.dto;

import java.math.BigDecimal;

public record ProductSearchResult(
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
