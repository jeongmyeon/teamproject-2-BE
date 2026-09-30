package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.infra.gemini.GeminiGenerationClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 원본 평가 질문을 Gemini로 paraphrase(다르게 표현한 질문)해 같은 정답 기준을 갖는
 * 새 평가 질문 목록으로 변환한다.
 */
@Component
@RequiredArgsConstructor
public class ParaphraseGenerator {

    private final GeminiGenerationClient generationClient;

    List<String> parseLines(String response, int count) {
        if (response == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String raw : response.split("\\r?\\n")) {
            String cleaned = raw.replaceAll("^[\\s\\-*\\d.)\"'“”]+", "").trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            lines.add(cleaned);
            if (lines.size() >= count) {
                break;
            }
        }
        return lines;
    }
}
