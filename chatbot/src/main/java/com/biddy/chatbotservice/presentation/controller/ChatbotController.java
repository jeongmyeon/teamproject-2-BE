package com.biddy.chatbotservice.presentation.controller;

import com.biddy.chatbotservice.application.ChatbotQueryService;
import com.biddy.chatbotservice.application.KnowledgeIngestionService;
import com.biddy.chatbotservice.application.RetrievalAccuracyEvalService;
import com.biddy.chatbotservice.presentation.dto.ChatbotQueryRequest;
import com.biddy.chatbotservice.presentation.dto.ChatbotQueryResponse;
import com.biddy.chatbotservice.presentation.dto.ReingestResponse;
import com.biddy.chatbotservice.presentation.dto.RetrievalAccuracyReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Chatbot", description = "RAG 기반 FAQ 챗봇 API")
@RestController
@RequestMapping("/api/chatbot")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotQueryService chatbotQueryService;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final RetrievalAccuracyEvalService retrievalAccuracyEvalService;

    @Operation(summary = "챗봇에게 질문", description = "FAQ/정책 문서를 검색해 Gemini 2.5 Flash-Lite가 답변을 생성한다.")
    @PostMapping("/query")
    public ChatbotQueryResponse query(@Valid @RequestBody ChatbotQueryRequest request) {
        return chatbotQueryService.ask(request.question());
    }

    @Operation(summary = "knowledge 문서 재적재", description = "FAQ/정책 문서를 다시 읽어 임베딩하고 pgvector 테이블을 갱신한다.")
    @PostMapping("/admin/reingest")
    public ReingestResponse reingest() {
        int count = knowledgeIngestionService.reingestAll();
        return new ReingestResponse(count);
    }

    @Operation(
            summary = "검색 정확도(Top-K) 평가",
            description = "knowledge 청크 자신의 질문으로 Top-K별 검색 정확도(Recall@K)를 측정한다. " +
                    "자기 질문 기반 self-retrieval이라 실제 사용자 질문보다 낙관적인 상한선 추정치다."
    )
    @PostMapping("/admin/eval/retrieval-accuracy")
    public RetrievalAccuracyReport evaluateRetrievalAccuracy(
            @RequestParam(defaultValue = "1,2,4,8") List<Integer> topKs) {
        return retrievalAccuracyEvalService.evaluate(topKs);
    }
}
