package com.biddy.chatbotservice.infra.gemini.dto;

import java.util.List;

public record GenerateContentRequest(List<Content> contents) {

    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    public static GenerateContentRequest of(String prompt) {
        return new GenerateContentRequest(List.of(new Content(List.of(new Part(prompt)))));
    }
}
