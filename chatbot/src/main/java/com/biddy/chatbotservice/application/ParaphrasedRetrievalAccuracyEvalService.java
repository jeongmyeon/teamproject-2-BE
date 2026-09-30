package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.domain.model.DocumentChunk;
import com.biddy.chatbotservice.domain.model.EvalQuestion;
import com.biddy.chatbotservice.domain.model.QuestionRankResult;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gemini로 생성한 paraphrase(다르게 표현한 질문) 질의셋으로 Top-K별 검색 정확도를 평가한다.
 * self-retrieval 평가(RetrievalAccuracyEvalService)보다 실제 사용자 질문에 가까운
 * 더 엄격한 평가 기준을 제공한다.
 */
@Service
@RequiredArgsConstructor
public class ParaphrasedRetrievalAccuracyEvalService {

    private final KnowledgeIngestionService knowledgeIngestionService;
    private final EvalQuestionExtractor evalQuestionExtractor;
    private final ParaphraseGenerator paraphraseGenerator;
    private final RetrievalRankFinder retrievalRankFinder;

    public RetrievalAccuracyReport evaluate(List<Integer> kValues, int paraphrasesPerQuestion) {
        int maxK = kValues.stream().max(Integer::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("topKs 파라미터가 비어 있습니다."));

        List<DocumentChunk> chunks = knowledgeIngestionService.loadChunks();
        List<EvalQuestion> originalQuestions = evalQuestionExtractor.extract(chunks);
        List<EvalQuestion> paraphrasedQuestions =
                paraphraseGenerator.generateParaphrasedQuestions(originalQuestions, paraphrasesPerQuestion);

        List<QuestionRankResult> rankResults = paraphrasedQuestions.stream()
                .map(q -> new QuestionRankResult(q.question(), retrievalRankFinder.findRank(q, maxK)))
                .toList();

        return RetrievalAccuracyCalculator.calculate(rankResults, kValues);
    }
}
