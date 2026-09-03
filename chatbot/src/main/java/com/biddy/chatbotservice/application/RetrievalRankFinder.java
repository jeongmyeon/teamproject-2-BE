package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.domain.model.RetrievedChunk;
import com.biddy.chatbotservice.infra.gemini.GeminiEmbeddingClient;
import com.biddy.chatbotservice.infra.persistence.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 평가 질문 1건을 임베딩·검색해서, 정답 청크가 검색 결과에서 몇 번째 순위인지 찾는다.
 */
@Component
@RequiredArgsConstructor
public class RetrievalRankFinder {

    private final GeminiEmbeddingClient embeddingClient;
    private final DocumentChunkRepository documentChunkRepository;

    public Optional<Integer> findRank(EvalQuestion evalQuestion, int maxK) {
        float[] queryEmbedding = embeddingClient.embed(evalQuestion.question(), GeminiEmbeddingClient.TASK_TYPE_QUERY);
        List<RetrievedChunk> retrieved = documentChunkRepository.findSimilar(queryEmbedding, maxK);

        for (int i = 0; i < retrieved.size(); i++) {
            if (retrieved.get(i).content().equals(evalQuestion.expectedContent())) {
                return Optional.of(i + 1);
            }
        }
        return Optional.empty();
    }
}
