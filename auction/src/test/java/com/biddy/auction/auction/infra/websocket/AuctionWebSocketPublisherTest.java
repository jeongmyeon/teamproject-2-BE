package com.biddy.auction.auction.infra.websocket;

import com.biddy.auction.bid.config.BidRealtimeProperties;
import com.biddy.auction.bid.infra.redis.BidRealtimeEvent;
import com.biddy.auction.bid.infra.redis.BidRealtimeRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuctionWebSocketPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private BidRealtimeRedisPublisher redisPublisher;

    private BidRealtimeProperties realtimeProperties;
    private AuctionWebSocketPublisher publisher;

    @BeforeEach
    void setUp() {
        realtimeProperties = new BidRealtimeProperties();
        publisher = new AuctionWebSocketPublisher(
                messagingTemplate,
                redisPublisher,
                realtimeProperties
        );
    }

    @Test
    @DisplayName("direct 모드에서는 현재 인스턴스의 WebSocket 구독자에게 입찰을 전송한다")
    void publishBidDirectly() {
        publisher.publishBid("A-001", 3_000L, 2, 7L);

        ArgumentCaptor<AuctionWebSocketMessage> messageCaptor =
                ArgumentCaptor.forClass(AuctionWebSocketMessage.class);
        verify(messagingTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("/topic/auctions/A-001"),
                messageCaptor.capture()
        );
        verify(redisPublisher, never()).publish(any());

        AuctionWebSocketMessage message = messageCaptor.getValue();
        assertThat(message.type()).isEqualTo("BID");
        assertThat(message.currentBid()).isEqualTo(3_000L);
        assertThat(message.bidCount()).isEqualTo(2);
        assertThat(message.bidderId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("redis 모드에서는 모든 인스턴스가 수신하도록 Redis에 입찰을 발행한다")
    void publishBidThroughRedis() {
        realtimeProperties.setRedisProjectionEnabled(true);
        realtimeProperties.setWebsocketSource(BidRealtimeProperties.WebSocketSource.REDIS);

        publisher.publishBid("A-001", 3_000L, 2, 7L);

        verify(redisPublisher).publish(new BidRealtimeEvent("A-001", 3_000L, 2, 7L));
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("Redis 발행 실패 시 현재 인스턴스의 WebSocket으로 입찰을 전송한다")
    void fallBackToDirectPublishingWhenRedisFails() {
        realtimeProperties.setRedisProjectionEnabled(true);
        realtimeProperties.setWebsocketSource(BidRealtimeProperties.WebSocketSource.REDIS);
        given(redisPublisher.publish(any())).willThrow(new IllegalStateException("redis unavailable"));

        publisher.publishBid("A-001", 3_000L, 2, 7L);

        verify(messagingTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("/topic/auctions/A-001"),
                any(AuctionWebSocketMessage.class)
        );
    }
}
