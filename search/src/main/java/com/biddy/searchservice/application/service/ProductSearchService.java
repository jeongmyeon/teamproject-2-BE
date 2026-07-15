package com.biddy.searchservice.application.service;

import com.biddy.searchservice.infrastructure.client.product.ProductClient;
import com.biddy.searchservice.presentation.dto.ProductSearchResult;
import com.biddy.searchservice.presentation.dto.SearchRequest;
import com.biddy.searchservice.presentation.dto.SearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final TextEmbeddingService textEmbeddingService;
    private final ProductClient productClient;
    private final KeywordSuggestionService keywordSuggestionService;
    private final LlmRecommendationService llmRecommendationService;
    private final MemberSearchHistoryService memberSearchHistoryService;

    @Value("${biddy.search.candidate-size:30}")
    private int defaultCandidateSize;

    @Value("${biddy.search.recommendation-size:2}")
    private int recommendationSize;

    public SearchResponse search(SearchRequest request, Long memberId) {
        String query = request.query().trim();
        int size = request.size() == null ? defaultCandidateSize : request.size();

        keywordSuggestionService.save(query);
        memberSearchHistoryService.record(memberId, query);

        List<Double> queryEmbedding = textEmbeddingService.embed(query);
        List<ProductSearchResult> products = productClient.searchByEmbedding(queryEmbedding, size);
        var recommendedProducts = llmRecommendationService.recommend(query, products, recommendationSize);

        return new SearchResponse(query, recommendedProducts, products);
    }
}
