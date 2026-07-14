package com.biddy.chatbotservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatbot.rag")
public record RagProperties(
        int topK,
        String chunkSourcePath
) {
}
