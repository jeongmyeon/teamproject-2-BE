package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.config.RagProperties;
import com.biddy.chatbotservice.domain.model.RetrievedChunk;
import com.biddy.chatbotservice.infra.gemini.GeminiEmbeddingClient;
import com.biddy.chatbotservice.infra.gemini.GeminiGenerationClient;
import com.biddy.chatbotservice.infra.persistence.DocumentChunkRepository;
import com.biddy.chatbotservice.presentation.dto.ChatbotQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 질문 임베딩 -> pgvector 유사도 검색 -> Gemini 2.5 Flash-Lite 답변 생성으로 이어지는
 * RAG 파이프라인의 핵심 서비스.
 */
@Service
@RequiredArgsConstructor
public class ChatbotQueryService {

    private final RagProperties ragProperties;
    private final GeminiEmbeddingClient embeddingClient;
    private final GeminiGenerationClient generationClient;
    private final DocumentChunkRepository documentChunkRepository;

    public ChatbotQueryResponse ask(String question) {
        float[] queryEmbedding = embeddingClient.embed(question, GeminiEmbeddingClient.TASK_TYPE_QUERY);
        List<RetrievedChunk> retrieved = documentChunkRepository.findSimilar(queryEmbedding, ragProperties.topK());

        if (retrieved.isEmpty()) {
            return new ChatbotQueryResponse(
                    "죄송합니다, 관련된 정책/FAQ 정보를 찾지 못했습니다. 다른 질문으로 다시 물어봐 주세요.",
                    List.of()
            );
        }

        String prompt = buildPrompt(question, retrieved);
        String answer = generationClient.generate(prompt);

        Set<String> sources = retrieved.stream()
                .map(RetrievedChunk::source)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new ChatbotQueryResponse(answer, List.copyOf(sources));
    }

    private String buildPrompt(String question, List<RetrievedChunk> retrieved) {
        String context = retrieved.stream()
                .map(RetrievedChunk::content)
                .collect(Collectors.joining("\n---\n"));

        return """
                너는 중고 경매 플랫폼 'Biddy'의 FAQ/정책 안내 챗봇이다.
                아래 [참고 문서]에 있는 내용만 근거로 답변하고, 참고 문서에 없는 내용은
                추측하지 말고 "해당 내용은 안내된 정책에서 확인되지 않습니다"라고 답하라.
                답변은 한국어로, 친절하고 간결하게 작성하라.

                [참고 문서]
                %s

                [사용자 질문]
                %s
                """.formatted(context, question);
    }
}
