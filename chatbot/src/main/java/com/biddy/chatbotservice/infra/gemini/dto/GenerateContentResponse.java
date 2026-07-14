package com.biddy.chatbotservice.infra.gemini.dto;

import java.util.List;

public record GenerateContentResponse(List<Candidate> candidates) {

    public record Candidate(Content content) {
    }

    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    /**
     * 첫 번째 candidate의 첫 번째 text part를 꺼낸다. 응답이 비어 있으면 안내 문구를 반환한다.
     */
    public String firstText() {
        if (candidates == null || candidates.isEmpty()) {
            return "죄송합니다, 답변을 생성하지 못했습니다.";
        }
        Content content = candidates.get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return "죄송합니다, 답변을 생성하지 못했습니다.";
        }
        return content.parts().get(0).text();
    }
}
