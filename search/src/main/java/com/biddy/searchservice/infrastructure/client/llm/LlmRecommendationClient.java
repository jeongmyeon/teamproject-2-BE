package com.biddy.searchservice.infrastructure.client.llm;

import com.biddy.searchservice.presentation.dto.ProductSearchResult;

import java.util.List;
import java.util.Map;

public interface LlmRecommendationClient {

    Map<Long, String> recommend(String query, List<ProductSearchResult> candidates, int recommendationSize);
}
