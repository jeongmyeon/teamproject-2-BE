package com.biddy.auction.bid.application.service;

import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.model.AuctionStatus;
import com.biddy.auction.auction.domain.repository.AuctionRepository;
import com.biddy.auction.auction.infra.websocket.AuctionWebSocketPublisher;
import com.biddy.auction.bid.application.dto.PlaceBidCommand;
import com.biddy.auction.bid.application.dto.PlaceBidResult;
import com.biddy.auction.bid.domain.model.Bid;
import com.biddy.auction.bid.domain.repository.BidRepository;
import com.biddy.auction.common.exception.BusinessException;
import com.biddy.auction.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlaceBidServiceTest {

    @InjectMocks
    private BidService bidService;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionWebSocketPublisher webSocketPublisher;

    @Mock
    private AuctionRepository auctionRepository;

    private Auction liveAuction;

    @BeforeEach
    void setUp() {
        liveAuction = Auction.builder()
                .auctionId("A-001")
                .sellerId(10L)
                .productId(1L)
                .startPrice(100000L)
                .minIncrement(10000L)
                .currentBid(500000L)
                .bidCount(5)
                .status(AuctionStatus.LIVE)
                .endsAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    @Nested
    @DisplayName("입찰 성공")
    class PlaceBidSuccess {

        @Test
        @DisplayName("유효한 입찰 금액으로 입찰에 성공한다")
        void placeBid_success() {
            // given
            PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 520000L);

            given(auctionRepository.findById("A-001")).willReturn(Optional.of(liveAuction));
            given(auctionRepository.findByIdForUpdate("A-001")).willReturn(Optional.of(liveAuction));
            given(bidRepository.save(any(Bid.class))).willReturn(
                    Bid.builder().bidId(101L).auctionId("A-001").bidderId(42L).amount(520000L).build()
            );

            // when
            PlaceBidResult result = bidService.placeBid(command);

            // then
            assertThat(result.bidId()).isEqualTo(101L);
            assertThat(result.amount()).isEqualTo(520000L);
            assertThat(result.currentBid()).isEqualTo(520000L);
            assertThat(result.bidCount()).isEqualTo(6);
            assertThat(liveAuction.getCurrentBid()).isEqualTo(520000L);
            assertThat(liveAuction.getCurrentBidderId()).isEqualTo(42L);
            assertThat(liveAuction.getBidCount()).isEqualTo(6);
            verify(bidRepository).save(any(Bid.class));
            verify(auctionRepository).save(liveAuction);
            verify(webSocketPublisher).publishBid("A-001", 520000L, 6, 42L);
        }

        @Test
        @DisplayName("최소 입찰 단위 정확히 만족하면 성공한다")
        void placeBid_exactMinIncrement_success() {
            // given: currentBid=500000, minIncrement=10000 → 510000 이상
            PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 510000L);

            given(auctionRepository.findById("A-001")).willReturn(Optional.of(liveAuction));
            given(auctionRepository.findByIdForUpdate("A-001")).willReturn(Optional.of(liveAuction));
            given(bidRepository.save(any(Bid.class))).willReturn(
                    Bid.builder().bidId(102L).auctionId("A-001").bidderId(42L).amount(510000L).build()
            );

            // when
            PlaceBidResult result = bidService.placeBid(command);

            // then
            assertThat(result.amount()).isEqualTo(510000L);
        }
    }

    @Nested
    @DisplayName("1차 검증 실패 (Pre-Lock)")
    class PreLockValidationFailure {

        @Test
        @DisplayName("존재하지 않는 경매에 입찰하면 AUCTION_NOT_FOUND")
        void placeBid_auctionNotFound() {
            PlaceBidCommand command = new PlaceBidCommand("A-999", 42L, 520000L);
            given(auctionRepository.findById("A-999")).willReturn(Optional.empty());

            assertThatThrownBy(() -> bidService.placeBid(command))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_NOT_FOUND);

            verify(auctionRepository, never()).findByIdForUpdate(any());
            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("종료된 경매에 입찰하면 AUCTION_ALREADY_ENDED")
        void placeBid_auctionEnded() {
            Auction endedAuction = Auction.builder()
                    .auctionId("A-001").sellerId(10L).productId(1L)
                    .startPrice(100000L).minIncrement(10000L).currentBid(500000L)
                    .status(AuctionStatus.ENDED).endsAt(LocalDateTime.now().minusHours(1))
                    .build();

            PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 520000L);
            given(auctionRepository.findById("A-001")).willReturn(Optional.of(endedAuction));

            assertThatThrownBy(() -> bidService.placeBid(command))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_ALREADY_ENDED);

            verify(auctionRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("본인 경매에 입찰하면 SELF_BID_NOT_ALLOWED")
        void placeBid_selfBid() {
            PlaceBidCommand command = new PlaceBidCommand("A-001", 10L, 520000L); // sellerId == bidderId

            given(auctionRepository.findById("A-001")).willReturn(Optional.of(liveAuction));

            assertThatThrownBy(() -> bidService.placeBid(command))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SELF_BID_NOT_ALLOWED);

            verify(auctionRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("최소 입찰 단위 미달이면 BID_AMOUNT_TOO_LOW")
        void placeBid_amountTooLow() {
            // currentBid=500000, minIncrement=10000 → 510000 미만
            PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 509999L);

            given(auctionRepository.findById("A-001")).willReturn(Optional.of(liveAuction));

            assertThatThrownBy(() -> bidService.placeBid(command))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BID_AMOUNT_TOO_LOW);

            verify(auctionRepository, never()).findByIdForUpdate(any());
        }
    }

    @Nested
    @DisplayName("최종 검증 실패 (Post-Lock)")
    class PostLockValidationFailure {

        @Test
        @DisplayName("락 획득 후 경매가 종료되었으면 AUCTION_ALREADY_ENDED")
        void placeBid_endedAfterLock() {
            Auction endedAfterLock = Auction.builder()
                    .auctionId("A-001").sellerId(10L).productId(1L)
                    .startPrice(100000L).minIncrement(10000L).currentBid(500000L)
                    .status(AuctionStatus.ENDED).endsAt(LocalDateTime.now().minusSeconds(1))
                    .build();

            PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 520000L);

            given(auctionRepository.findById("A-001")).willReturn(Optional.of(liveAuction));
            given(auctionRepository.findByIdForUpdate("A-001")).willReturn(Optional.of(endedAfterLock));

            assertThatThrownBy(() -> bidService.placeBid(command))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_ALREADY_ENDED);

            verify(bidRepository, never()).save(any());
        }

        @Test
        @DisplayName("락 획득 후 더 높은 입찰이 존재하면 BID_AMOUNT_TOO_LOW")
        void placeBid_outbidAfterLock() {
            // 1차 검증 시점: currentBid=500000 → 520000 OK
            // 락 획득 후: currentBid=600000 → 520000 부족
            Auction updatedAuction = Auction.builder()
                    .auctionId("A-001").sellerId(10L).productId(1L)
                    .startPrice(100000L).minIncrement(10000L).currentBid(600000L)
                    .bidCount(6).status(AuctionStatus.LIVE).endsAt(LocalDateTime.now().plusHours(1))
                    .build();

            PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 520000L);

            given(auctionRepository.findById("A-001")).willReturn(Optional.of(liveAuction));
            given(auctionRepository.findByIdForUpdate("A-001")).willReturn(Optional.of(updatedAuction));

            assertThatThrownBy(() -> bidService.placeBid(command))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BID_AMOUNT_TOO_LOW);

            verify(bidRepository, never()).save(any());
        }
    }
}
