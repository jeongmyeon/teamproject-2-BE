package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.EvalQuestion;
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

    public List<EvalQuestion> generateParaphrasedQuestions(List<EvalQuestion> originalQuestions, int perQuestion) {
        List<EvalQuestion> result = new ArrayList<>();
        for (EvalQuestion original : originalQuestions) {
            List<String> paraphrases = paraphrase(original.question(), perQuestion);
            for (String paraphrased : paraphrases) {
                result.add(new EvalQuestion(paraphrased, original.expectedSource(), original.expectedContent()));
            }
        }
        return result;
    }

    private List<String> paraphrase(String question, int count) {
        String prompt = buildPrompt(question, count);
        String response = generationClient.generate(prompt);
        return parseLines(response, count);
    }

    private String buildPrompt(String question, int count) {
        return """
                아래 질문을 실제 사용자가 챗봇에게 물어볼 법한 자연스러운 다른 표현으로 %d개 바꿔 써줘.
                원래 의미는 그대로 유지하되, 표현과 어순은 다르게 해줘.
                각 결과는 한 줄에 하나씩, 번호나 설명 없이 질문 문장만 출력해줘.

                질문: %s
                """.formatted(count, question);
    }

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
