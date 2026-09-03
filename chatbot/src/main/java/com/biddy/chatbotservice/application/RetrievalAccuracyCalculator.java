package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.QuestionRankResult;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;

import java.util.ArrayList;
import java.util.List;

/**
 * 질문별 정답 순위 목록과 K값 목록을 받아 K별 정확도(Accuracy@K)를 계산하는 순수 함수.
 * DB·외부 API에 의존하지 않는다.
 */
public class RetrievalAccuracyCalculator {

    public static RetrievalAccuracyReport calculate(List<QuestionRankResult> rankResults, List<Integer> kValues) {
        if (kValues.isEmpty()) {
            throw new IllegalArgumentException("topKs 파라미터가 비어 있습니다.");
        }

        int total = rankResults.size();
        List<RetrievalAccuracyReport.TopKAccuracy> topKAccuracies = new ArrayList<>();
        for (int k : kValues) {
            long hitCount = rankResults.stream()
                    .filter(r -> r.rank().isPresent() && r.rank().get() <= k)
                    .count();
            double accuracy = total == 0 ? 0.0 : (double) hitCount / total;
            topKAccuracies.add(new RetrievalAccuracyReport.TopKAccuracy(k, (int) hitCount, accuracy));
        }

        int maxK = kValues.stream().max(Integer::compareTo).orElseThrow();
        List<String> missedQuestions = rankResults.stream()
                .filter(r -> r.rank().isEmpty() || r.rank().get() > maxK)
                .map(QuestionRankResult::question)
                .toList();

        return new RetrievalAccuracyReport(total, topKAccuracies, missedQuestions);
    }
}
