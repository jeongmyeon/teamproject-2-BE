package com.biddy.chatbotservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GeminiClientConfig {

    @Bean
    public RestClient geminiRestClient(GeminiProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("x-goog-api-key", properties.apiKey())
                .build();
    }
}
