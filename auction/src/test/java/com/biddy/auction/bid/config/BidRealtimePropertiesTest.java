package com.biddy.auction.bid.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BidRealtimePropertiesTest {

    @Test
    @DisplayName("Redis WebSocket 소스는 Redis projection이 활성화되어야 한다")
    void redisSourceRequiresProjection() {
        BidRealtimeProperties properties = new BidRealtimeProperties();
        properties.setWebsocketSource(BidRealtimeProperties.WebSocketSource.REDIS);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BID_REDIS_PROJECTION_ENABLED=true");
    }

    @Test
    @DisplayName("direct 모드는 Redis projection 없이 사용할 수 있다")
    void directSourceDoesNotRequireProjection() {
        BidRealtimeProperties properties = new BidRealtimeProperties();

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
