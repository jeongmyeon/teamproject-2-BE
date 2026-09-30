package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.DocumentChunk;
import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ParaphrasedRetrievalAccuracyEvalServiceTest {

    @Mock private KnowledgeIngestionService knowledgeIngestionService;
    @Mock private EvalQuestionExtractor evalQuestionExtractor;
    @Mock private ParaphraseGenerator paraphraseGenerator;
    @Mock private RetrievalRankFinder retrievalRankFinder;

    @InjectMocks
    private ParaphrasedRetrievalAccuracyEvalService evalService;

    @Test
    void 청크_로드부터_paraphrase_생성_정확도_계산까지_순서대로_호출해서_결과를_조립한다() {
        DocumentChunk chunk = new DocumentChunk("a.md", 0, "content");
        EvalQuestion original = new EvalQuestion("원본 질문", "a.md", "content");
        EvalQuestion paraphrased = new EvalQuestion("바뀐 질문", "a.md", "content");
        given(knowledgeIngestionService.loadChunks()).willReturn(List.of(chunk));
        given(evalQuestionExtractor.extract(List.of(chunk))).willReturn(List.of(original));
        given(paraphraseGenerator.generateParaphrasedQuestions(List.of(original), 2))
                .willReturn(List.of(paraphrased, paraphrased));
        given(retrievalRankFinder.findRank(paraphrased, 4)).willReturn(Optional.of(1));

        RetrievalAccuracyReport report = evalService.evaluate(List.of(1, 4), 2);

        assertThat(report.totalQuestions()).isEqualTo(2);
        assertThat(report.results()).hasSize(2);
        assertThat(report.missedQuestions()).isEmpty();
    }

    @Test
    void kValues가_비어있으면_예외를_던진다() {
        assertThatThrownBy(() -> evalService.evaluate(List.of(), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
