package com.biddy.chatbotservice.infra.gemini.dto;

import java.util.List;

public record EmbedContentRequest(
        String taskType,
        Content content,
        Integer outputDimensionality
) {
    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    public static EmbedContentRequest of(String text, String taskType, Integer outputDimensionality) {
        return new EmbedContentRequest(taskType, new Content(List.of(new Part(text))), outputDimensionality);
    }
}
