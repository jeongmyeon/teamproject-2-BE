package com.biddy.searchservice.presentation.dto;

public record RecommendedProductResult(
        ProductSearchResult product,
        String reason
) {
}
