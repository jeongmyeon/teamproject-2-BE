package com.biddy.searchservice.presentation.dto;

import java.util.List;

public record PopularKeywordResponse(
        List<PopularKeywordResult> keywords
) {
}
