package com.biddy.order.outbox.scheduler;

import com.biddy.order.outbox.domain.OutboxEvent;
import com.biddy.order.outbox.domain.OutboxEventRepository;
import com.biddy.order.outbox.domain.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kafka", name = "enabled", havingValue = "true")
public class OutboxRelayScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> orderKafkaTemplate;

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
                orderKafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .whenComplete((result, throwable) -> {
                            if (throwable != null) {
                                log.error("Failed to relay outbox event id: {} to topic {}", event.getId(), event.getTopic(), throwable);
                            } else {
                                event.markAsProcessed();
                                outboxEventRepository.save(event); // update status
                                log.info("Successfully relayed outbox event id: {} to topic {}", event.getId(), event.getTopic());
                            }
                        });
            } catch (Exception e) {
                log.error("Unexpected error relaying outbox event id: {}", event.getId(), e);
            }
        }
    }
}
