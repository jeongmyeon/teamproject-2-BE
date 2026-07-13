package com.biddy.memberservice.infrastructure.kafka;

import com.biddy.memberservice.application.event.MemberEventPublisher;
import com.biddy.memberservice.application.event.MemberSignupEvent;
import com.biddy.memberservice.application.event.MemberWithdrawEvent;
import com.biddy.memberservice.outbox.domain.OutboxEvent;
import com.biddy.memberservice.outbox.domain.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Kafka에 직접 발행하지 않고, outbox 테이블에 저장만 한다.
// 실제 발행은 OutboxRelayScheduler가 폴링하며 처리한다. (outbox 패턴)
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaMemberEventPublisher implements MemberEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Override
    @SneakyThrows
    public void publishSignup(Long memberId) {
        MemberSignupEvent event = new MemberSignupEvent(memberId);
        save("MEMBER", String.valueOf(memberId), "member-signup", event);
    }

    @Override
    @SneakyThrows
    public void publishWithdraw(Long memberId) {
        MemberWithdrawEvent event = new MemberWithdrawEvent(memberId);
        save("MEMBER", String.valueOf(memberId), "member-withdraw", event);
    }

    @SneakyThrows
    private void save(String aggregateType, String aggregateId, String topic, Object event) {
        String payload = objectMapper.writeValueAsString(event);
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .topic(topic)
                .payload(payload)
                .build();
        outboxEventRepository.save(outboxEvent);
        log.info("Saved event to outbox for topic {}: {}", topic, payload);
    }
}
