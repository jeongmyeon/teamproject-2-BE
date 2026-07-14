package com.biddy.chatbotservice.infra.gemini;

import com.biddy.chatbotservice.config.GeminiProperties;
import com.biddy.chatbotservice.infra.gemini.dto.GenerateContentRequest;
import com.biddy.chatbotservice.infra.gemini.dto.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Gemini generateContent API 호출을 담당한다. (모델: gemini-2.5-flash-lite)
 */
@Component
@RequiredArgsConstructor
public class GeminiGenerationClient {

    private final RestClient geminiRestClient;
    private final GeminiProperties properties;

    public String generate(String prompt) {
        GenerateContentRequest request = GenerateContentRequest.of(prompt);

        GenerateContentResponse response = geminiRestClient.post()
                .uri("/models/{model}:generateContent", properties.generationModel())
                .body(request)
                .retrieve()
                .body(GenerateContentResponse.class);

        if (response == null) {
            throw new IllegalStateException("Gemini 답변 생성 응답이 비어 있습니다.");
        }
        return response.firstText();
    }
}
