package com.biddy.auction.bid.infra.redis;

public record BidRealtimeEvent(
        String auctionId,
        Long currentBid,
        Integer bidCount,
        Long bidderId
) {
}
