package com.biddy.auction.auction.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionTest {

    @Test
    @DisplayName("입찰 적용 시 현재가, 현재 최고 입찰자와 입찰 수를 함께 갱신한다")
    void applyBid_updatesCurrentBidStateTogether() {
        Auction auction = Auction.builder()
                .auctionId("A-001")
                .productId(1L)
                .sellerId(10L)
                .startPrice(100000L)
                .minIncrement(10000L)
                .currentBid(500000L)
                .currentBidderId(20L)
                .bidCount(5)
                .endsAt(LocalDateTime.now().plusHours(1))
                .build();

        auction.applyBid(520000L, 42L);

        assertThat(auction.getCurrentBid()).isEqualTo(520000L);
        assertThat(auction.getCurrentBidderId()).isEqualTo(42L);
        assertThat(auction.getBidCount()).isEqualTo(6);
    }
}
