package com.biddy.searchservice.presentation.dto;

import java.util.List;

public record SearchHistoryRecommendationResponse(
        List<SearchHistoryKeywordResult> keywords,
        List<ProductSearchResult> products
) {
}
