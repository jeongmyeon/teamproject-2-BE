package com.biddy.searchservice.application.service;

import com.biddy.searchservice.domain.model.MemberSearchHistory;
import com.biddy.searchservice.infrastructure.client.product.ProductClient;
import com.biddy.searchservice.infrastructure.persistence.MemberSearchHistoryJpaRepository;
import com.biddy.searchservice.presentation.dto.ProductSearchResult;
import com.biddy.searchservice.presentation.dto.SearchHistoryKeywordResult;
import com.biddy.searchservice.presentation.dto.SearchHistoryRecommendationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MemberSearchHistoryService {

    private final MemberSearchHistoryJpaRepository memberSearchHistoryJpaRepository;
    private final TextEmbeddingService textEmbeddingService;
    private final ProductClient productClient;

    @Value("${biddy.search.history-keyword-size:5}")
    private int historyKeywordSize;

    @Transactional
    public void record(Long memberId, String keyword) {
        String normalizedKeyword = normalize(keyword);
        if (memberId == null || normalizedKeyword.isBlank()) {
            return;
        }

        MemberSearchHistory history = memberSearchHistoryJpaRepository
                .findByMemberIdAndKeyword(memberId, normalizedKeyword)
                .map(existingHistory -> {
                    existingHistory.increase();
                    return existingHistory;
                })
                .orElseGet(() -> MemberSearchHistory.create(memberId, normalizedKeyword));
        memberSearchHistoryJpaRepository.save(history);
    }

    @Transactional(readOnly = true)
    public SearchHistoryRecommendationResponse recommend(Long memberId, int size) {
        int safeSize = Math.min(Math.max(size, 1), 30);
        List<MemberSearchHistory> histories = findTopHistories(memberId);
        if (histories.isEmpty()) {
            return new SearchHistoryRecommendationResponse(List.of(), List.of());
        }

        Map<Long, ScoredProduct> scoredProducts = new LinkedHashMap<>();
        for (int historyIndex = 0; historyIndex < histories.size(); historyIndex++) {
            MemberSearchHistory history = histories.get(historyIndex);
            List<Double> embedding = textEmbeddingService.embed(history.getKeyword());
            List<ProductSearchResult> products = productClient.searchByEmbedding(embedding, safeSize);

            for (int productIndex = 0; productIndex < products.size(); productIndex++) {
                ProductSearchResult product = products.get(productIndex);
                double score = historyScore(history, historyIndex, productIndex, safeSize);
                scoredProducts.merge(
                        product.productId(),
                        new ScoredProduct(product, score),
                        (left, right) -> left.score >= right.score ? left : right
                );
            }
        }

        List<ProductSearchResult> products = scoredProducts.values()
                .stream()
                .sorted((left, right) -> Double.compare(right.score, left.score))
                .limit(safeSize)
                .map(ScoredProduct::product)
                .toList();

        List<SearchHistoryKeywordResult> keywords = histories.stream()
                .map(history -> new SearchHistoryKeywordResult(
                        history.getKeyword(),
                        safeCount(history),
                        history.getUpdatedAt().toString()
                ))
                .toList();

        return new SearchHistoryRecommendationResponse(keywords, products);
    }

    private List<MemberSearchHistory> findTopHistories(Long memberId) {
        if (memberId == null) {
            return List.of();
        }

        try {
            return memberSearchHistoryJpaRepository.findByMemberIdOrderByCountDescUpdatedAtDesc(
                    memberId,
                    PageRequest.of(0, historyKeywordSize)
            );
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    private double historyScore(
            MemberSearchHistory history,
            int historyIndex,
            int productIndex,
            int requestedSize
    ) {
        long count = Math.max(safeCount(history), 1L);
        int keywordRankWeight = Math.max(historyKeywordSize - historyIndex, 1);
        int productRankWeight = Math.max(requestedSize - productIndex, 1);
        return count * keywordRankWeight * productRankWeight;
    }

    private long safeCount(MemberSearchHistory history) {
        return history.getCount() == null ? 0L : history.getCount();
    }

    private String normalize(String keyword) {
        return keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
    }

    private record ScoredProduct(ProductSearchResult product, double score) {
    }
}
