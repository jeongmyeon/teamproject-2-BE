package com.biddy.chatbotservice.presentation.controller;

import com.biddy.chatbotservice.application.ChatbotQueryService;
import com.biddy.chatbotservice.application.KnowledgeIngestionService;
import com.biddy.chatbotservice.presentation.dto.ChatbotQueryRequest;
import com.biddy.chatbotservice.presentation.dto.ChatbotQueryResponse;
import com.biddy.chatbotservice.presentation.dto.ReingestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Chatbot", description = "RAG 기반 FAQ 챗봇 API")
@RestController
@RequestMapping("/api/chatbot")
@RequiredArgsConstructor
public class ChatbotController {

    private final ChatbotQueryService chatbotQueryService;
    private final KnowledgeIngestionService knowledgeIngestionService;

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
}
