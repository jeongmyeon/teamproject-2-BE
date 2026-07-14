package com.biddy.chatbotservice.application;

import com.biddy.chatbotservice.config.GeminiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 앱 기동 시 knowledge 문서가 아직 적재되지 않았다면 백그라운드로 초기 적재를 수행한다.
 * Gemini API를 문서 개수만큼 호출하므로 별도 스레드에서 실행해 헬스체크/Eureka 등록을 막지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupIngestionRunner implements ApplicationRunner {

    private final KnowledgeIngestionService ingestionService;
    private final GeminiProperties geminiProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (geminiProperties.apiKey() == null || geminiProperties.apiKey().isBlank()) {
            log.warn("GEMINI_API_KEY가 설정되지 않아 초기 knowledge 적재를 건너뜁니다.");
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                ingestionService.ingestIfEmpty();
            } catch (Exception e) {
                log.error("초기 knowledge 적재 중 오류가 발생했습니다. /api/chatbot/admin/reingest 로 수동 재시도할 수 있습니다.", e);
            }
        });
    }
}
