package com.biddy.chatbotservice.infra.gemini;

import com.biddy.chatbotservice.config.GeminiProperties;
import com.biddy.chatbotservice.infra.gemini.dto.EmbedContentRequest;
import com.biddy.chatbotservice.infra.gemini.dto.EmbedContentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Gemini embedContent API 호출을 담당한다. (모델: gemini-embedding-001)
 */
@Component
@RequiredArgsConstructor
public class GeminiEmbeddingClient {

    public static final String TASK_TYPE_DOCUMENT = "RETRIEVAL_DOCUMENT";
    public static final String TASK_TYPE_QUERY = "RETRIEVAL_QUERY";

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;

    public float[] embed(String text, String taskType) {
        EmbedContentRequest request = EmbedContentRequest.of(text, taskType, properties.embeddingDimension());

        EmbedContentResponse response = geminiRestClient.post()
                .uri("/models/{model}:embedContent", properties.embeddingModel())
                .body(request)
                .retrieve()
                .body(EmbedContentResponse.class);

        if (response == null || response.embedding() == null) {
            throw new IllegalStateException("Gemini 임베딩 응답이 비어 있습니다.");
        }
        return response.embedding().values();
    }
}
