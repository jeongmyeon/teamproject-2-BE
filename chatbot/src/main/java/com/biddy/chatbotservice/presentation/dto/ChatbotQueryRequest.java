package com.biddy.chatbotservice.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatbotQueryRequest(
        @NotBlank(message = "질문을 입력해 주세요.")
        String question
) {
}
