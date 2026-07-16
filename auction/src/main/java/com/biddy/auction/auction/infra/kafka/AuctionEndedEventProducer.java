package com.biddy.auction.auction.infra.kafka;

import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.bid.domain.model.Bid;
import com.biddy.auction.outbox.domain.OutboxEvent;
import com.biddy.auction.outbox.domain.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 경매 종료 이벤트 Producer (Outbox 패턴).
 *
 * <p>낙찰 시 {@code auction.ended} 토픽으로 이벤트를 발행하여
 * Order Service가 주문을 자동 생성하도록 한다.</p>
 *
 * <p>유찰(UNSOLD)은 주문 생성이 불필요하므로 이벤트를 발행하지 않는다.</p>
 *
 * <p>Transactional Outbox 패턴을 사용하여 이벤트 발행의 원자성을 보장한다.
 * 비즈니스 트랜잭션과 동일한 트랜잭션에서 이벤트를 DB에 저장하고,
 * 별도의 스케줄러가 주기적으로 Kafka로 발행한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuctionEndedEventProducer {

    private static final String TOPIC = "auction.ended";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 낙찰 이벤트를 발행한다 (Outbox 패턴).
     *
     * @param auction 종료된 경매
     * @param topBid  최고 입찰 (낙찰자)
     */
    public void publish(Auction auction, Bid topBid) {
        AuctionEndedEventPayload payload = AuctionEndedEventPayload.from(auction, topBid);

        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("AUCTION")
                    .aggregateId(auction.getAuctionId())
                    .topic(TOPIC)
                    .payload(json)
                    .build();
            outboxEventRepository.save(outboxEvent);

            log.info("Outbox 이벤트 저장: auctionId={}, winnerId={}, finalBid={}",
                    auction.getAuctionId(), topBid.getBidderId(), topBid.getAmount());
        } catch (JsonProcessingException e) {
            log.error("이벤트 직렬화 실패: auctionId={}", auction.getAuctionId(), e);
            throw new IllegalStateException("경매 종료 이벤트 직렬화에 실패했습니다.", e);
        }
    }
}
