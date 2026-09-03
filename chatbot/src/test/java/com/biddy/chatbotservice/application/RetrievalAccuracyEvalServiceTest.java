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
class RetrievalAccuracyEvalServiceTest {

    @Mock private KnowledgeIngestionService knowledgeIngestionService;
    @Mock private EvalQuestionExtractor evalQuestionExtractor;
    @Mock private RetrievalRankFinder retrievalRankFinder;

    @InjectMocks
    private RetrievalAccuracyEvalService evalService;

    @Test
    void 청크_로드부터_정확도_계산까지_순서대로_호출해서_결과를_조립한다() {
        DocumentChunk chunk = new DocumentChunk("a.md", 0, "content");
        EvalQuestion evalQuestion = new EvalQuestion("질문", "a.md", "content");
        given(knowledgeIngestionService.loadChunks()).willReturn(List.of(chunk));
        given(evalQuestionExtractor.extract(List.of(chunk))).willReturn(List.of(evalQuestion));
        given(retrievalRankFinder.findRank(evalQuestion, 4)).willReturn(Optional.of(1));

        RetrievalAccuracyReport report = evalService.evaluate(List.of(1, 4));

        assertThat(report.totalQuestions()).isEqualTo(1);
        assertThat(report.results()).hasSize(2);
        assertThat(report.missedQuestions()).isEmpty();
    }

    @Test
    void kValues가_비어있으면_예외를_던진다() {
        assertThatThrownBy(() -> evalService.evaluate(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
