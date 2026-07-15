package com.biddy.searchservice.presentation.dto;

import java.util.List;

public record KeywordSuggestionResponse(
        String keyword,
        List<String> suggestions
) {
}
