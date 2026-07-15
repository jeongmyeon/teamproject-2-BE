package com.biddy.searchservice.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Service
public class TextEmbeddingService {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public TextEmbeddingService(
            RestClient.Builder restClientBuilder,
            @Value("${biddy.embedding.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${biddy.embedding.api-key:}") String apiKey,
            @Value("${biddy.embedding.model:text-embedding-3-small}") String model,
            @Value("${biddy.embedding.dimensions:1536}") int dimensions
    ) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .build();
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
    }

    public List<Double> embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is required to create search embeddings.");
        }

        EmbeddingRequest request = new EmbeddingRequest(
                model,
                text,
                "float",
                dimensions
        );

        try {
            EmbeddingResponse response = restClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(EmbeddingResponse.class);

            if (response == null || response.data() == null || response.data().isEmpty()) {
                throw new IllegalStateException("OpenAI embedding response did not contain embedding data.");
            }

            return response.data().getFirst().embedding();
        } catch (RestClientException e) {
            throw new IllegalStateException("Failed to create OpenAI search embedding.", e);
        }
    }

    private record EmbeddingRequest(
            String model,
            String input,
            String encoding_format,
            int dimensions
    ) {
    }

    private record EmbeddingResponse(
            List<EmbeddingData> data
    ) {
    }

    private record EmbeddingData(
            List<Double> embedding
    ) {
    }
}
