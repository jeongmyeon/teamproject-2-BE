package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.QuestionRankResult;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalAccuracyCalculatorTest {

    @Test
    void 모든_질문이_1위로_정답을_찾으면_K1에서_정확도가_1_0이다() {
        List<QuestionRankResult> results = List.of(
                new QuestionRankResult("q1", Optional.of(1)),
                new QuestionRankResult("q2", Optional.of(1))
        );

        RetrievalAccuracyReport report = RetrievalAccuracyCalculator.calculate(results, List.of(1));

        assertThat(report.totalQuestions()).isEqualTo(2);
        assertThat(report.results().get(0).k()).isEqualTo(1);
        assertThat(report.results().get(0).hitCount()).isEqualTo(2);
        assertThat(report.results().get(0).accuracy()).isEqualTo(1.0);
    }

    @Test
    void K값별로_다른_정확도를_계산한다() {
        List<QuestionRankResult> results = List.of(
                new QuestionRankResult("q1", Optional.of(1)),
                new QuestionRankResult("q2", Optional.of(3)),
                new QuestionRankResult("q3", Optional.of(5)),
                new QuestionRankResult("q4", Optional.empty())
        );

        RetrievalAccuracyReport report = RetrievalAccuracyCalculator.calculate(results, List.of(1, 4, 8));

        assertThat(report.results().get(0).k()).isEqualTo(1);
        assertThat(report.results().get(0).hitCount()).isEqualTo(1);
        assertThat(report.results().get(1).k()).isEqualTo(4);
        assertThat(report.results().get(1).hitCount()).isEqualTo(2);
        assertThat(report.results().get(2).k()).isEqualTo(8);
        assertThat(report.results().get(2).hitCount()).isEqualTo(3);
    }

    @Test
    void 가장_큰_K로도_못_찾은_질문은_missedQuestions에_포함된다() {
        List<QuestionRankResult> results = List.of(
                new QuestionRankResult("q1", Optional.of(1)),
                new QuestionRankResult("q2", Optional.of(10)),
                new QuestionRankResult("q3", Optional.empty())
        );

        RetrievalAccuracyReport report = RetrievalAccuracyCalculator.calculate(results, List.of(1, 4));

        assertThat(report.missedQuestions()).containsExactlyInAnyOrder("q2", "q3");
    }

    @Test
    void kValues가_비어있으면_예외를_던진다() {
        assertThatThrownBy(() -> RetrievalAccuracyCalculator.calculate(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
