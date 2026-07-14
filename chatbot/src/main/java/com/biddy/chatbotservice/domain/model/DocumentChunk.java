package com.biddy.chatbotservice.domain.model;

/**
 * 임베딩 전, 문서를 쪼갠 하나의 청크(조각)를 나타낸다.
 */
public record DocumentChunk(
        String source,
        int chunkIndex,
        String content
) {
}
