package com.biddy.auction.bid.infra.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BidRealtimeRedisPublisher {

    private static final String KEY_PREFIX = "auction:realtime:";
    private static final String EVENT_CHANNEL_SUFFIX = ":events";

    private static final DefaultRedisScript<Long> APPLY_AND_PUBLISH_SCRIPT = new DefaultRedisScript<>("""
            local currentBidCount = redis.call('HGET', KEYS[1], 'bidCount')
            if currentBidCount and tonumber(ARGV[2]) <= tonumber(currentBidCount) then
                return 0
            end
            redis.call('HSET', KEYS[1],
                'currentBid', ARGV[1],
                'bidCount', ARGV[2],
                'bidderId', ARGV[3])
            redis.call('PUBLISH', KEYS[2], ARGV[4])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public boolean publish(BidRealtimeEvent event) {
        String serializedEvent = serialize(event);
        Long applied = redisTemplate.execute(
                APPLY_AND_PUBLISH_SCRIPT,
                List.of(projectionKey(event.auctionId()), eventChannel(event.auctionId())),
                String.valueOf(event.currentBid()),
                String.valueOf(event.bidCount()),
                String.valueOf(event.bidderId()),
                serializedEvent
        );
        return Long.valueOf(1L).equals(applied);
    }

    private String serialize(BidRealtimeEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("입찰 실시간 이벤트 직렬화에 실패했습니다.", exception);
        }
    }

    static String projectionKey(String auctionId) {
        return KEY_PREFIX + "{" + auctionId + "}";
    }

    static String eventChannel(String auctionId) {
        return projectionKey(auctionId) + EVENT_CHANNEL_SUFFIX;
    }
}
