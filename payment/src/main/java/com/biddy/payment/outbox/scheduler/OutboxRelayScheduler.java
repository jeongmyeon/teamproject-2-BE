package com.biddy.payment.outbox.scheduler;

import com.biddy.payment.outbox.domain.OutboxEvent;
import com.biddy.payment.outbox.domain.OutboxEventRepository;
import com.biddy.payment.outbox.domain.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

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
