package com.biddy.chatbotservice.domain.model;

/**
 * 검색 정확도 평가용 질문 1건. knowledge 청크에서 추출한 질문과,
 * 그 질문의 정답이 되어야 할 청크 정보(출처, 전체 내용).
 */
public record EvalQuestion(
        String question,
        String expectedSource,
        String expectedContent
) {
}
