package com.biddy.chatbotservice.domain.model;

import java.util.Optional;

/**
 * 평가 질문 1건에 대해, 정답 청크가 검색 결과에서 몇 번째 순위(1부터)로 나왔는지.
 * maxK 안에서 못 찾았으면 empty.
 */
public record QuestionRankResult(
        String question,
        Optional<Integer> rank
) {
}
