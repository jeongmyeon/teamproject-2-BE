package com.biddy.searchservice.infrastructure.elasticsearch;

public record SearchKeywordDocument(
        String keyword,
        Long count,
        String updatedAt
) {
}
