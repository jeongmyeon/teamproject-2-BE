package com.biddy.searchservice.application.service;

import com.biddy.searchservice.infrastructure.client.llm.LlmRecommendationClient;
import com.biddy.searchservice.presentation.dto.ProductSearchResult;
import com.biddy.searchservice.presentation.dto.RecommendedProductResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LlmRecommendationService {

    private static final String DEFAULT_REASON = "검색어와 의미적으로 가까운 상품입니다.";

    private final LlmRecommendationClient llmRecommendationClient;

    public List<RecommendedProductResult> recommend(
            String query,
            List<ProductSearchResult> candidates,
            int recommendationSize
    ) {
        if (candidates.isEmpty() || recommendationSize <= 0) {
            return List.of();
        }

        Map<Long, ProductSearchResult> productsById = candidates.stream()
                .collect(Collectors.toMap(
                        ProductSearchResult::productId,
                        Function.identity(),
                        (first, second) -> first
                ));

        Map<Long, String> llmReasons = llmRecommendationClient.recommend(query, candidates, recommendationSize);
        List<RecommendedProductResult> recommendations = new ArrayList<>();
        Set<Long> selectedProductIds = new LinkedHashSet<>();

        for (Map.Entry<Long, String> entry : llmReasons.entrySet()) {
            ProductSearchResult product = productsById.get(entry.getKey());
            if (product == null || selectedProductIds.contains(product.productId())) {
                continue;
            }
            recommendations.add(new RecommendedProductResult(product, entry.getValue()));
            selectedProductIds.add(product.productId());
            if (recommendations.size() == recommendationSize) {
                return recommendations;
            }
        }

        for (ProductSearchResult product : candidates) {
            if (selectedProductIds.contains(product.productId())) {
                continue;
            }
            recommendations.add(new RecommendedProductResult(product, DEFAULT_REASON));
            if (recommendations.size() == recommendationSize) {
                break;
            }
        }

        return recommendations;
    }
}
