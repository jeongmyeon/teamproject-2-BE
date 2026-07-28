package com.biddy.order.order.infra.event;

import com.biddy.order.order.domain.model.Order;
import com.biddy.order.order.domain.model.OrderInfo;
import com.biddy.order.order.domain.model.OrderStatus;
import com.biddy.order.order.domain.model.OrderType;
import com.biddy.order.order.domain.repository.OrderRepository;
import com.biddy.order.order.infra.event.dto.AuctionEndedEventPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kafka", name = "enabled", havingValue = "true")
public class AuctionEndedEventListener {

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auction.ended", groupId = "order-service")
    @Transactional
    public void handleAuctionEnded(String payload) {
        log.info("Received AuctionEndedEvent: {}", payload);
        try {
            AuctionEndedEventPayload event = objectMapper.readValue(payload, AuctionEndedEventPayload.class);
            
            // 1. 이미 동일한 auctionId로 주문이 생성되었는지 확인 (멱등성 보장)
            Optional<Order> existingOrder = orderRepository.findByAuctionId(event.auctionId());
            if (existingOrder.isPresent()) {
                log.info("Order already exists for auctionId={}. Skipping order creation.", event.auctionId());
                return;
            }

            // 2. 주문 정보 빌드
            // 경매 낙찰 건은 결제가 안 된 경매 상태인 PENDING으로 생성하되, orderType=AUCTION으로 표시하여
            // 30분 배치 취소 로직에서 제외되고 paymentDeadline까지 유지됩니다.
            Order order = Order.builder()
                    .userId(event.winnerId())
                    .status(OrderStatus.PENDING)
                    .totalPrice(event.finalBid())
                    .orderType(OrderType.AUCTION)
                    .auctionId(event.auctionId())
                    .paymentDeadline(event.paymentDeadline())
                    .orderInfos(new ArrayList<>())
                    .build();

            // 3. 주문 상세(OrderInfo) 생성 및 연동
            OrderInfo orderInfo = OrderInfo.builder()
                    .order(order)
                    .orderPrice(event.finalBid())
                    .quantity(1) // 경매 상품 수량은 무조건 1개
                    .productId(event.productId())
                    .sellerId(event.sellerId())
                    .build();

            order.getOrderInfos().add(orderInfo);

            // 4. 저장
            orderRepository.save(order);
            log.info("Successfully created auction order from Kafka event. OrderId={}, AuctionId={}, WinnerId={}, Price={}", 
                    order.getId(), event.auctionId(), event.winnerId(), event.finalBid());

        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize AuctionEndedEventPayload", e);
            throw new RuntimeException("Failed to deserialize AuctionEndedEventPayload", e);
        } catch (Exception e) {
            log.error("Error processing AuctionEndedEvent", e);
            throw new RuntimeException("Error processing AuctionEndedEvent", e);
        }
    }
}
