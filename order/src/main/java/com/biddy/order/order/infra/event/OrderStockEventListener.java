package com.biddy.order.order.infra.event;

import com.biddy.order.order.application.usecase.OrderUseCase;
import com.biddy.order.order.domain.model.Order;
import com.biddy.order.order.domain.repository.OrderRepository;
import com.biddy.order.order.infra.event.dto.StockDeductFailedEvent;
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
public class OrderStockEventListener {

    private final OrderUseCase orderUseCase;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.stock.deduct.failed", groupId = "order-service")
    public void handleStockDeductFailed(String payload) {
        log.info("Received StockDeductFailedEvent: {}", payload);
        try {
            StockDeductFailedEvent event = objectMapper.readValue(payload, StockDeductFailedEvent.class);
            
            Order order = orderRepository.findById(event.orderId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 주문입니다. orderId=" + event.orderId()));
            
            orderUseCase.cancelOrder(order.getUserId(), order.getId());
            log.info("Order status updated to CANCELLED due to stock deduction failure. orderId: {}", event.orderId());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize StockDeductFailedEvent", e);
            throw new RuntimeException("Failed to deserialize StockDeductFailedEvent", e);
        } catch (Exception e) {
            log.error("Error processing StockDeductFailedEvent", e);
            throw new RuntimeException("Error processing StockDeductFailedEvent", e);
        }
    }
}
