package com.biddy.chatbotservice.domain.model;

/**
 * pgvector 유사도 검색 결과로 조회된 청크.
 */
public record RetrievedChunk(
        String source,
        String content,
        double similarity
) {
}
