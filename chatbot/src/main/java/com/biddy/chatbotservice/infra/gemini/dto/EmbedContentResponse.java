package com.biddy.chatbotservice.infra.gemini.dto;

public record EmbedContentResponse(Embedding embedding) {

    public record Embedding(float[] values) {
    }
}
