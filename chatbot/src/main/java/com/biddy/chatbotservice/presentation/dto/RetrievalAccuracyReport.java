package com.biddy.chatbotservice.presentation.dto;

import java.util.List;

public record RetrievalAccuracyReport(
        int totalQuestions,
        List<TopKAccuracy> results,
        List<String> missedQuestions
) {
    public record TopKAccuracy(int k, int hitCount, double accuracy) {
    }
}
