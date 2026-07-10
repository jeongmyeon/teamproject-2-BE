package com.biddy.order.order.infra.event;

import com.biddy.order.order.application.dto.event.CancelPaymentEvent;
import com.biddy.order.order.application.dto.event.DecreaseStockEvent;
import com.biddy.order.order.application.dto.event.PurchaseConfirmedEvent;
import com.biddy.order.order.application.dto.event.RestoreStockEvent;
import com.biddy.order.outbox.domain.OutboxEvent;
import com.biddy.order.outbox.domain.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kafka", name = "enabled", havingValue = "true")
public class OrderEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    // 1. 결제취소 이벤트 발행 (수신처: payment)
    public void publishCancelPayment(Long orderId, Long userId) {
        String topic = "order.cancelled";
        CancelPaymentEvent event = new CancelPaymentEvent(
                UUID.randomUUID(),
                orderId,
                userId,
                "주문 취소",
                LocalDateTime.now()
        );
        send(topic, String.valueOf(orderId), event);
    }

    // 2. 정산요청 이벤트 발행 (수신처: settlement)
    public void publishRequestSettlement(Long orderId, Long userId) {
        String topic = "purchase.confirmed";
        PurchaseConfirmedEvent event = new PurchaseConfirmedEvent(
                UUID.randomUUID(),
                orderId,
                LocalDateTime.now()
        );
        send(topic, String.valueOf(orderId), event);
    }

    // 3. 재고차감 이벤트 발행 (수신처: product)
    public void publishDecreaseStock(Long orderId, Long productId, Integer quantity) {
        String topic = "order.stock.deduct";
        DecreaseStockEvent event = new DecreaseStockEvent(orderId, productId, quantity);
        send(topic, String.valueOf(productId), event);
    }

    // 4. 재고원복 이벤트 발행 (수신처: product)
    public void publishRestoreStock(Long orderId, Long productId, Integer quantity) {
        String topic = "order.stock.restore";
        RestoreStockEvent event = new RestoreStockEvent(orderId, productId, quantity);
        send(topic, String.valueOf(productId), event);
    }

    private void send(String topic, String key, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("ORDER")
                    .aggregateId(key)
                    .topic(topic)
                    .payload(payload)
                    .build();
            outboxEventRepository.save(outboxEvent);
            log.info("Successfully saved event to outbox for topic {}: {}", topic, payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Kafka event for topic: {}", topic, e);
        }
    }
}
