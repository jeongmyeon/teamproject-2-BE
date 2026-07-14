package com.biddy.chatbotservice.presentation.dto;

import java.util.List;

public record ChatbotQueryResponse(
        String answer,
        List<String> sources
) {
}
