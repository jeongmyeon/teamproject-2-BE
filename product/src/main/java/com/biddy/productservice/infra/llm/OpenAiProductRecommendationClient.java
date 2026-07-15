package com.biddy.productservice.infra.llm;

import com.biddy.productservice.domain.model.Product;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class OpenAiProductRecommendationClient implements ProductRecommendationClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final String model;

    public OpenAiProductRecommendationClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${llm.openai.enabled:true}") boolean enabled,
            @Value("${llm.openai.api-key:}") String apiKey,
            @Value("${llm.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${llm.openai.model:gpt-4o-mini}") String model
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public List<Long> selectRecommendedProductIds(String query, List<Product> products, int recommendationLimit) {
        if (products.isEmpty()) {
            return List.of();
        }
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "상품 AI 검색 기능이 비활성화되어 있습니다.");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OPENAI_API_KEY가 설정되어 있지 않습니다.");
        }

        ChatCompletionResponse response = restClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(new ChatCompletionRequest(
                        model,
                        List.of(
                                new Message("system", systemPrompt()),
                                new Message("user", userPrompt(query, products, recommendationLimit))
                        ),
                        0.0
                ))
                .retrieve()
                .body(ChatCompletionResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 추천 응답이 비어 있습니다.");
        }

        String content = response.choices().get(0).message().content();
        if (!StringUtils.hasText(content)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 추천 응답이 비어 있습니다.");
        }
        return parseRecommendedIds(content, products, recommendationLimit);
    }

    private String systemPrompt() {
        return """
                너는 중고거래/경매 서비스 Biddy의 상품 재정렬 도우미다.
                반드시 제공된 상품 후보의 id만 사용한다.
                사용자 의도에 가장 잘 맞는 상품 id를 최대 recommendationLimit개 고른다.
                설명 문장을 쓰지 말고 JSON만 응답한다.
                응답 형식은 반드시 {"recommendedProductIds":[1,2]} 이다.
                """;
    }

    private String userPrompt(String query, List<Product> products, int recommendationLimit) {
        return """
                사용자 검색어:
                %s

                recommendationLimit:
                %d

                상품 후보:
                %s

                상품 후보 중 사용자 검색 의도에 가장 잘 맞는 상품 id만 JSON으로 반환해줘.
                """.formatted(query, recommendationLimit, formatProducts(products));
    }

    private String formatProducts(List<Product> products) {
        if (products.isEmpty()) {
            return "상품 후보 없음";
        }

        StringBuilder builder = new StringBuilder();
        for (Product product : products) {
            builder.append("- id: ").append(product.getId()).append('\n');
            builder.append("  name: ").append(nullToBlank(product.getName())).append('\n');
            builder.append("  brand: ").append(nullToBlank(product.getBrand())).append('\n');
            builder.append("  category: ").append(nullToBlank(product.getCategory())).append('\n');
            builder.append("  description: ").append(nullToBlank(product.getDescription())).append('\n');
            builder.append("  price: ").append(product.getPrice()).append('\n');
            builder.append("  status: ").append(nullToBlank(product.getStatus())).append('\n');
        }
        return builder.toString();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private List<Long> parseRecommendedIds(String content, List<Product> products, int recommendationLimit) {
        try {
            RecommendationResponse response = objectMapper.readValue(content.trim(), RecommendationResponse.class);
            if (response.recommendedProductIds() == null) {
                return List.of();
            }

            java.util.Set<Long> candidateIds = products.stream()
                    .map(Product::getId)
                    .collect(java.util.stream.Collectors.toSet());

            return response.recommendedProductIds().stream()
                    .filter(candidateIds::contains)
                    .distinct()
                    .limit(Math.max(recommendationLimit, 0))
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 추천 응답을 해석할 수 없습니다.");
        }
    }

    private record ChatCompletionRequest(String model, List<Message> messages, double temperature) {
    }

    private record Message(String role, String content) {
    }

    private record ChatCompletionResponse(List<Choice> choices) {
    }

    private record Choice(Message message) {
    }

    private record RecommendationResponse(List<Long> recommendedProductIds) {
    }
}
