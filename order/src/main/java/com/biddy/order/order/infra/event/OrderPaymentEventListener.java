package com.biddy.order.order.infra.event;

import com.biddy.order.order.application.usecase.OrderUseCase;
import com.biddy.order.order.domain.model.OrderStatus;
import com.biddy.order.order.infra.event.dto.PaymentCompletedEvent;
import com.biddy.order.order.infra.event.dto.PaymentFailedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kafka", name = "enabled", havingValue = "true")
public class OrderPaymentEventListener {

    private final OrderUseCase orderUseCase;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment.completed", groupId = "order-service")
    public void handlePaymentCompleted(String payload) {
        log.info("Received PaymentCompletedEvent: {}", payload);
        try {
            PaymentCompletedEvent event = objectMapper.readValue(payload, PaymentCompletedEvent.class);
            orderUseCase.changeStatus(event.buyerId(), event.orderId(), OrderStatus.PAID);
            log.info("Order status updated to PAID for orderId: {}", event.orderId());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize PaymentCompletedEvent", e);
            throw new RuntimeException("Failed to deserialize PaymentCompletedEvent", e);
        } catch (Exception e) {
            log.error("Error processing PaymentCompletedEvent", e);
            throw new RuntimeException("Error processing PaymentCompletedEvent", e);
        }
    }

    @KafkaListener(topics = "payment.failed", groupId = "order-service")
    public void handlePaymentFailed(String payload) {
        log.info("Received PaymentFailedEvent: {}", payload);
        try {
            PaymentFailedEvent event = objectMapper.readValue(payload, PaymentFailedEvent.class);
            orderUseCase.changeStatus(event.buyerId(), event.orderId(), OrderStatus.CANCELLED);
            log.info("Order status updated to CANCELLED for orderId: {}", event.orderId());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize PaymentFailedEvent", e);
            throw new RuntimeException("Failed to deserialize PaymentFailedEvent", e);
        } catch (Exception e) {
            log.error("Error processing PaymentFailedEvent", e);
            throw new RuntimeException("Error processing PaymentFailedEvent", e);
        }
    }
}
