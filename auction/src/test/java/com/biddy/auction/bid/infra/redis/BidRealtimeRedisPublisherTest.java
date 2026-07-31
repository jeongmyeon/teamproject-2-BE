package com.biddy.auction.bid.infra.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BidRealtimeRedisPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("최신 입찰을 Redis projection에 반영하고 같은 슬롯의 채널로 발행한다")
    void publishLatestBid() throws Exception {
        BidRealtimeEvent event = new BidRealtimeEvent("A-001", 3_000L, 2, 7L);
        given(objectMapper.writeValueAsString(event)).willReturn("{\"auctionId\":\"A-001\"}");
        given(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("auction:realtime:{A-001}", "auction:realtime:{A-001}:events")),
                eq("3000"),
                eq("2"),
                eq("7"),
                eq("{\"auctionId\":\"A-001\"}")
        )).willReturn(1L);

        boolean applied = new BidRealtimeRedisPublisher(redisTemplate, objectMapper).publish(event);

        assertThat(applied).isTrue();
        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("auction:realtime:{A-001}", "auction:realtime:{A-001}:events")),
                eq("3000"),
                eq("2"),
                eq("7"),
                eq("{\"auctionId\":\"A-001\"}")
        );
    }

    @Test
    @DisplayName("이미 처리한 bidCount이면 중복 입찰 이벤트로 판단한다")
    void ignoreDuplicateBidCount() throws Exception {
        BidRealtimeEvent event = new BidRealtimeEvent("A-001", 3_000L, 2, 7L);
        given(objectMapper.writeValueAsString(event)).willReturn("{}");
        given(redisTemplate.execute(
                any(RedisScript.class),
                any(List.class),
                any(),
                any(),
                any(),
                any()
        )).willReturn(0L);

        boolean applied = new BidRealtimeRedisPublisher(redisTemplate, objectMapper).publish(event);

        assertThat(applied).isFalse();
    }
}
