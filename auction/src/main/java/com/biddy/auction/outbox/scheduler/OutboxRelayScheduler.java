package com.biddy.auction.outbox.scheduler;

import com.biddy.auction.outbox.domain.OutboxEvent;
import com.biddy.auction.outbox.domain.OutboxEventRepository;
import com.biddy.auction.outbox.domain.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 이벤트를 Kafka로 발행하는 스케줄러.
 *
 * <p>주기적으로 PENDING 상태의 이벤트를 조회하여 Kafka로 발행한다.
 * 발행 성공 시 PROCESSED로, 실패 시 재시도 횟수를 증가시키고 최대 재시도 횟수 초과 시 FAILED로 변경한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.scheduler.max-retry-count:5}")
    private int maxRetryCount;

    @Value("${outbox.scheduler.send-timeout-seconds:10}")
    private long sendTimeoutSeconds;

    /**
     * 주기적으로 PENDING 상태의 Outbox 이벤트를 Kafka로 발행한다.
     * 기본 5초마다 실행되며, application.yml에서 설정 가능하다.
     */
    @Scheduled(fixedDelayString = "${outbox.scheduler.delay:5000}")
    @Transactional
    public void relayOutboxEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Relaying {} pending outbox events to Kafka...", pendingEvents.size());

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(sendTimeoutSeconds, TimeUnit.SECONDS);
                event.markAsProcessed();
                outboxEventRepository.save(event);
                log.info("Successfully relayed outbox event id: {} to topic {}", event.getId(), event.getTopic());
            } catch (Exception e) {
                log.error("Unexpected error relaying outbox event id: {}", event.getId(), e);
                event.markAsFailed(e, maxRetryCount);
                outboxEventRepository.save(event);
            }
        }
    }
}
