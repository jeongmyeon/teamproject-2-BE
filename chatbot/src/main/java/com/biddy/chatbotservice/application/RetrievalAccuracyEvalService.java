package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.DocumentChunk;
import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.domain.model.QuestionRankResult;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * knowledge 청크를 정답지 삼아 Top-K별 검색 정확도(Accuracy@K)를 평가한다.
 * self-retrieval(자기 질문으로 자기 청크 찾기) 방식이라 실제 사용자 질문보다
 * 낙관적인 상한선(upper bound) 추정치라는 점에 유의한다.
 */
@Service
@RequiredArgsConstructor
public class RetrievalAccuracyEvalService {

    private final KnowledgeIngestionService knowledgeIngestionService;
    private final EvalQuestionExtractor evalQuestionExtractor;
    private final RetrievalRankFinder retrievalRankFinder;

    public RetrievalAccuracyReport evaluate(List<Integer> kValues) {
        int maxK = kValues.stream().max(Integer::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("topKs 파라미터가 비어 있습니다."));

        List<DocumentChunk> chunks = knowledgeIngestionService.loadChunks();
        List<EvalQuestion> evalQuestions = evalQuestionExtractor.extract(chunks);

        List<QuestionRankResult> rankResults = evalQuestions.stream()
                .map(q -> new QuestionRankResult(q.question(), retrievalRankFinder.findRank(q, maxK)))
                .toList();

        return RetrievalAccuracyCalculator.calculate(rankResults, kValues);
    }
}
