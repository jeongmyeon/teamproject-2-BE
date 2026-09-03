package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.domain.model.RetrievedChunk;
import com.biddy.chatbotservice.infra.gemini.GeminiEmbeddingClient;
import com.biddy.chatbotservice.infra.persistence.DocumentChunkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RetrievalRankFinderTest {

    @Mock private GeminiEmbeddingClient embeddingClient;
    @Mock private DocumentChunkRepository documentChunkRepository;

    @InjectMocks
    private RetrievalRankFinder retrievalRankFinder;

    @Test
    void 정답_청크가_검색결과_2번째에_있으면_순위_2를_반환한다() {
        EvalQuestion evalQuestion = new EvalQuestion("질문", "source.md", "정답 청크 내용");
        float[] embedding = {0.1f, 0.2f};
        given(embeddingClient.embed("질문", GeminiEmbeddingClient.TASK_TYPE_QUERY)).willReturn(embedding);
        given(documentChunkRepository.findSimilar(embedding, 4)).willReturn(List.of(
                new RetrievedChunk("other.md", "다른 청크 내용", 0.9),
                new RetrievedChunk("source.md", "정답 청크 내용", 0.85)
        ));

        Optional<Integer> rank = retrievalRankFinder.findRank(evalQuestion, 4);

        assertThat(rank).contains(2);
    }

    @Test
    void 정답_청크가_검색결과에_없으면_빈값을_반환한다() {
        EvalQuestion evalQuestion = new EvalQuestion("질문", "source.md", "정답 청크 내용");
        float[] embedding = {0.1f, 0.2f};
        given(embeddingClient.embed("질문", GeminiEmbeddingClient.TASK_TYPE_QUERY)).willReturn(embedding);
        given(documentChunkRepository.findSimilar(embedding, 4)).willReturn(List.of(
                new RetrievedChunk("other.md", "다른 청크 내용", 0.9)
        ));

        Optional<Integer> rank = retrievalRankFinder.findRank(evalQuestion, 4);

        assertThat(rank).isEmpty();
    }
}
