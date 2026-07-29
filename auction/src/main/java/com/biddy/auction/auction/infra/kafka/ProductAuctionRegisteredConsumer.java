package com.biddy.auction.auction.infra.kafka;

import com.biddy.auction.auction.application.service.AuctionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Product Service의 경매 상품 등록 이벤트 Consumer.
 *
 * <p>토픽: {@code product.auction.registered}
 * Product Service에서 경매 상품이 등록되면 Kafka를 통해 이벤트를 수신하고,
 * 경매를 자동 생성한다.</p>
 *
 * <p>StringDeserializer로 수신된 JSON 문자열을 ObjectMapper로 직접 역직렬화한다.
 * Producer 측 직렬화 방식에 의존하지 않으므로 서비스 간 결합도가 낮다.</p>
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>Auction Service는 Product Service를 REST 호출하지 않는다</li>
 *   <li>Product가 데이터의 주인, Auction은 비정규화 복사본</li>
 *   <li>멱등성 보장 — auctionId 기반 중복 생성 방지</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductAuctionRegisteredConsumer {

    private final AuctionService auctionService;
    private final ObjectMapper objectMapper;

    /**
     * product.auction.registered 토픽에서 이벤트를 수신한다.
     *
     * @param message JSON 문자열 (StringDeserializer 사용)
     */
    @KafkaListener(topics = "product.auction.registered", groupId = "auction-service")
    public void consume(String message) {
        try {
            ProductAuctionRegisteredPayload payload =
                    objectMapper.readValue(message, ProductAuctionRegisteredPayload.class);

            log.info("경매 상품 등록 이벤트 수신: productId={}, sellerId={}, startPrice={}",
                    payload.productId(), payload.sellerId(), payload.startPrice());

            auctionService.createFromProduct(payload);
        } catch (Exception e) {
            log.error("경매 상품 등록 이벤트 처리 실패: {}", message, e);
            throw new IllegalArgumentException("잘못된 경매 상품 등록 이벤트입니다.", e);
        }
    }
}
