package com.biddy.searchservice.presentation.controller;

import com.biddy.searchservice.application.service.KeywordSuggestionService;
import com.biddy.searchservice.application.service.MemberSearchHistoryService;
import com.biddy.searchservice.application.service.ProductSearchService;
import com.biddy.searchservice.application.service.TextEmbeddingService;
import com.biddy.searchservice.presentation.dto.EmbeddingDebugResponse;
import com.biddy.searchservice.presentation.dto.KeywordSuggestionResponse;
import com.biddy.searchservice.presentation.dto.PopularKeywordResponse;
import com.biddy.searchservice.presentation.dto.SearchRequest;
import com.biddy.searchservice.presentation.dto.SearchHistoryRecommendationResponse;
import com.biddy.searchservice.presentation.dto.SearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.init}/search")
@RequiredArgsConstructor
public class SearchController {

    private final ProductSearchService productSearchService;
    private final KeywordSuggestionService keywordSuggestionService;
    private final TextEmbeddingService textEmbeddingService;
    private final MemberSearchHistoryService memberSearchHistoryService;

    @PostMapping
    @Operation(summary = "상품 의미 검색", description = "검색어를 임베딩한 뒤 Product Service에 유사 상품 후보 조회를 요청합니다.")
    public SearchResponse search(
            @Valid @RequestBody SearchRequest request,
            @RequestHeader(value = "X-Member-Id", required = false) Long memberId
    ) {
        return productSearchService.search(request, memberId);
    }

    @GetMapping("/recommendations/history")
    @Operation(summary = "내 검색 기록 기반 추천", description = "회원의 검색 기록 빈도를 가중치로 사용해 추천 상품을 반환합니다.")
    public SearchHistoryRecommendationResponse historyRecommendations(
            @RequestHeader("X-Member-Id") Long memberId,
            @RequestParam(defaultValue = "10") @Positive int size
    ) {
        return memberSearchHistoryService.recommend(memberId, size);
    }

    @GetMapping("/suggestions")
    @Operation(summary = "검색어 자동완성", description = "Elasticsearch에 저장된 검색어 기록을 prefix 기반으로 조회합니다.")
    public KeywordSuggestionResponse suggest(
            @RequestParam @NotBlank String keyword,
            @RequestParam(defaultValue = "10") @Positive int size
    ) {
        return keywordSuggestionService.suggest(keyword, size);
    }

    @PostMapping("/keywords")
    @Operation(summary = "검색어 기록 저장", description = "자동완성/연관검색어 후보로 사용할 검색어를 Elasticsearch에 저장합니다.")
    public void saveKeyword(@RequestParam @NotBlank String keyword) {
        keywordSuggestionService.save(keyword);
    }

    @GetMapping("/keywords/popular")
    @Operation(summary = "인기 검색어 조회", description = "검색 기록이 많이 쌓인 검색어를 count 기준으로 반환합니다.")
    public PopularKeywordResponse popularKeywords(
            @RequestParam(defaultValue = "10") @Positive int size
    ) {
        return keywordSuggestionService.popular(size);
    }

    @GetMapping("/debug/embedding")
    @Operation(summary = "검색어 임베딩 확인", description = "개발 확인용으로 검색어가 어떤 벡터로 변환되는지 반환합니다.")
    public EmbeddingDebugResponse debugEmbedding(@RequestParam @NotBlank String query) {
        var embedding = textEmbeddingService.embed(query);
        return new EmbeddingDebugResponse(query, embedding.size(), embedding);
    }

}
