package com.biddy.payment.payment.infrastructure.kafka.producer;

import com.biddy.payment.outbox.domain.OutboxEvent;
import com.biddy.payment.outbox.domain.OutboxEventRepository;
import com.biddy.payment.payment.domain.event.PaymentCompletedEvent;
import com.biddy.payment.payment.domain.event.PaymentFailedEvent;
import com.biddy.payment.payment.domain.event.PaymentRefundedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventProducer {

    private static final String PAYMENT_COMPLETED_TOPIC = "payment.completed";
    private static final String PAYMENT_FAILED_TOPIC = "payment.failed";
    private static final String PAYMENT_REFUNDED_TOPIC = "payment.refunded";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventProducer(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void publish(PaymentCompletedEvent event) {
        send(PAYMENT_COMPLETED_TOPIC, event.orderId(), event);
    }

    public void publish(PaymentFailedEvent event) {
        send(PAYMENT_FAILED_TOPIC, event.orderId(), event);
    }

    public void publish(PaymentRefundedEvent event) {
        send(PAYMENT_REFUNDED_TOPIC, event.orderId(), event);
    }

    private void send(String topic, Long orderId, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("PAYMENT")
                    .aggregateId(String.valueOf(orderId))
                    .topic(topic)
                    .payload(payload)
                    .build();
            outboxEventRepository.save(outboxEvent);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("결제 이벤트 직렬화에 실패했습니다.", exception);
        }
    }
}
