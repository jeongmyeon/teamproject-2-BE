package com.biddy.auction.bid.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "bid")
public class BidRealtimeProperties {

    private boolean redisProjectionEnabled;
    private WebSocketSource websocketSource = WebSocketSource.DIRECT;

    @PostConstruct
    void validate() {
        if (websocketSource == WebSocketSource.REDIS && !redisProjectionEnabled) {
            throw new IllegalStateException(
                    "BID_WEBSOCKET_SOURCE=redis requires BID_REDIS_PROJECTION_ENABLED=true"
            );
        }
    }

    public enum WebSocketSource {
        DIRECT,
        REDIS
    }
}
