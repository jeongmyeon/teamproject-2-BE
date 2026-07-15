package com.biddy.searchservice.presentation.dto;

import java.util.List;

public record SearchResponse(
        String query,
        List<RecommendedProductResult> recommendedProducts,
        List<ProductSearchResult> products
) {
}
