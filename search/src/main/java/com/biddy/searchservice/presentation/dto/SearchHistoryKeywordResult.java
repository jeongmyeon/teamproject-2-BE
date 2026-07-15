package com.biddy.searchservice.presentation.dto;

public record SearchHistoryKeywordResult(
        String keyword,
        Long count,
        String updatedAt
) {
}
