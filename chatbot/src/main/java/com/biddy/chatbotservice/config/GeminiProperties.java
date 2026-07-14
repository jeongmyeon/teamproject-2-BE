package com.biddy.chatbotservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatbot.gemini")
public record GeminiProperties(
        String apiKey,
        String baseUrl,
        String embeddingModel,
        int embeddingDimension,
        String generationModel
) {
}
