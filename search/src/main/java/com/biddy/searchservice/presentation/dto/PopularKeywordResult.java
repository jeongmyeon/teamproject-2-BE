package com.biddy.searchservice.presentation.dto;

public record PopularKeywordResult(
        String keyword,
        Long count
) {
}
