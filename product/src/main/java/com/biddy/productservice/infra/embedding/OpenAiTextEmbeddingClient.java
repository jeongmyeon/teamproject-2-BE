package com.biddy.productservice.infra.embedding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Component
public class OpenAiTextEmbeddingClient implements TextEmbeddingClient {

    private final RestClient restClient;
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public OpenAiTextEmbeddingClient(
            RestClient.Builder restClientBuilder,
            @Value("${embedding.openai.enabled:true}") boolean enabled,
            @Value("${embedding.openai.api-key:}") String apiKey,
            @Value("${embedding.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${embedding.openai.model:text-embedding-3-small}") String model,
            @Value("${embedding.openai.dimensions:1536}") int dimensions
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public List<Double> embed(String text) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "상품 임베딩 기능이 비활성화되어 있습니다.");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OPENAI_API_KEY가 설정되어 있지 않습니다.");
        }

        EmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(new EmbeddingRequest(model, text, dimensions))
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "임베딩 응답이 비어 있습니다.");
        }
        return response.data().get(0).embedding();
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private record EmbeddingRequest(String model, String input, int dimensions) {
    }

    private record EmbeddingResponse(List<EmbeddingData> data) {
    }

    private record EmbeddingData(List<Double> embedding) {
    }
}
