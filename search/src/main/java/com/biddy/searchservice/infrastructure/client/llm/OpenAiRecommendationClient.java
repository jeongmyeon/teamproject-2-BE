package com.biddy.searchservice.infrastructure.client.llm;

import com.biddy.searchservice.presentation.dto.ProductSearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiRecommendationClient implements LlmRecommendationClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String model;
    private final String apiKey;

    public OpenAiRecommendationClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${biddy.llm.enabled:false}") boolean enabled,
            @Value("${biddy.llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${biddy.llm.api-key:}") String apiKey,
            @Value("${biddy.llm.model:gpt-4o-mini}") String model
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.model = model;
        this.apiKey = apiKey;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    public Map<Long, String> recommend(String query, List<ProductSearchResult> candidates, int recommendationSize) {
        if (!enabled || apiKey == null || apiKey.isBlank() || candidates.isEmpty()) {
            return Map.of();
        }

        OpenAiChatRequest request = new OpenAiChatRequest(
                model,
                List.of(
                        new ChatMessage("system", systemPrompt(recommendationSize)),
                        new ChatMessage("user", userPrompt(query, candidates, recommendationSize))
                ),
                0.2
        );

        try {
            OpenAiChatResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(OpenAiChatResponse.class);

            if (response == null || response.choices().isEmpty()) {
                return Map.of();
            }

            String content = response.choices().getFirst().message().content();
            return parseRecommendationReasons(content);
        } catch (RestClientException | JsonProcessingException e) {
            return Map.of();
        }
    }

    private String systemPrompt(int recommendationSize) {
        return """
                너는 중고거래 상품 검색 결과를 재정렬하는 추천 보조자야.
                반드시 후보 상품 목록 안에서만 %d개 이하를 고르고, 없는 상품 ID를 만들면 안 돼.
                답변은 설명 문장 없이 JSON 배열만 반환해.
                형식: [{"productId":1,"reason":"추천 이유 한 문장"}]
                """.formatted(recommendationSize);
    }

    private String userPrompt(String query, List<ProductSearchResult> candidates, int recommendationSize) {
        List<Map<String, Object>> compactCandidates = candidates.stream()
                .limit(20)
                .map(product -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("productId", product.productId());
                    value.put("name", product.name());
                    value.put("price", product.price());
                    value.put("status", product.status());
                    value.put("stock", product.stock());
                    value.put("similarityScore", product.similarityScore());
                    return value;
                })
                .toList();

        return """
                검색어: %s
                추천 개수: %d
                후보 상품: %s
                """.formatted(query, recommendationSize, compactCandidates);
    }

    private Map<Long, String> parseRecommendationReasons(String content) throws JsonProcessingException {
        String json = stripCodeFence(content);
        List<RecommendationItem> items = objectMapper.readValue(json, new TypeReference<>() {
        });

        Map<Long, String> reasons = new LinkedHashMap<>();
        for (RecommendationItem item : items) {
            if (item.productId() == null || item.reason() == null || item.reason().isBlank()) {
                continue;
            }
            reasons.put(item.productId(), item.reason());
        }
        return reasons;
    }

    private String stripCodeFence(String content) {
        String value = content == null ? "" : content.trim();
        if (!value.startsWith("```")) {
            return value;
        }

        List<String> lines = new ArrayList<>(List.of(value.split("\\R")));
        if (!lines.isEmpty()) {
            lines.removeFirst();
        }
        if (!lines.isEmpty() && lines.getLast().startsWith("```")) {
            lines.removeLast();
        }
        return String.join("\n", lines).trim();
    }

    private record OpenAiChatRequest(
            String model,
            List<ChatMessage> messages,
            double temperature
    ) {
    }

    private record ChatMessage(
            String role,
            String content
    ) {
    }

    private record OpenAiChatResponse(
            List<Choice> choices
    ) {
    }

    private record Choice(
            ChatMessage message
    ) {
    }

    private record RecommendationItem(
            Long productId,
            String reason
    ) {
    }
}
