package com.biddy.auction.bid.infra.redis;

import com.biddy.auction.auction.infra.websocket.AuctionWebSocketPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BidRealtimeRedisSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final AuctionWebSocketPublisher webSocketPublisher;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String serializedEvent = RedisSerializer.string().deserialize(message.getBody());
        if (serializedEvent == null) {
            return;
        }

        try {
            BidRealtimeEvent event = objectMapper.readValue(serializedEvent, BidRealtimeEvent.class);
            webSocketPublisher.publishBidLocally(event);
        } catch (JsonProcessingException exception) {
            log.error("Redis 입찰 실시간 이벤트 역직렬화 실패", exception);
        }
    }
}
