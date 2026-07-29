package com.biddy.auction.bid.application.service;

import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.model.AuctionStatus;
import com.biddy.auction.auction.domain.repository.AuctionRepository;
import com.biddy.auction.bid.application.dto.PlaceBidCommand;
import com.biddy.auction.bid.application.dto.PlaceBidResult;
import com.biddy.auction.bid.domain.model.Bid;
import com.biddy.auction.bid.domain.repository.BidRepository;
import com.biddy.auction.common.exception.BusinessException;
import com.biddy.auction.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BidTransactionServiceTest {

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @InjectMocks
    private BidTransactionService transactionService;

    private Auction auction;

    @BeforeEach
    void setUp() {
        auction = Auction.builder()
                .auctionId("A-001")
                .sellerId(10L)
                .productId(1L)
                .startPrice(100000L)
                .currentBid(500000L)
                .minIncrement(10000L)
                .bidCount(5)
                .status(AuctionStatus.LIVE)
                .startsAt(LocalDateTime.now().minusHours(1))
                .endsAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    @Test
    @DisplayName("Bid 저장과 Auction 갱신을 flush한 뒤 결과를 반환한다")
    void executeBidTransaction_flushesBeforeReturning() {
        PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 520000L);
        Bid savedBid = Bid.builder()
                .bidId(101L)
                .auctionId("A-001")
                .bidderId(42L)
                .amount(520000L)
                .build();

        given(auctionRepository.findById("A-001")).willReturn(Optional.of(auction));
        given(bidRepository.save(any(Bid.class))).willReturn(savedBid);
        given(auctionRepository.save(auction)).willReturn(auction);

        PlaceBidResult result = transactionService.executeBidTransaction(command);

        assertThat(result).isEqualTo(new PlaceBidResult(101L, 520000L, 520000L, 6));
        InOrder order = inOrder(bidRepository, auctionRepository);
        order.verify(bidRepository).save(any(Bid.class));
        order.verify(auctionRepository).save(auction);
        order.verify(auctionRepository).flush();
    }

    @Test
    @DisplayName("업무 검증 실패 시 저장과 flush를 수행하지 않는다")
    void executeBidTransaction_validationFailure_doesNotWrite() {
        PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 509999L);
        given(auctionRepository.findById("A-001")).willReturn(Optional.of(auction));

        assertThatThrownBy(() -> transactionService.executeBidTransaction(command))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.BID_AMOUNT_TOO_LOW);

        verify(bidRepository, never()).save(any());
        verify(auctionRepository, never()).save(any());
        verify(auctionRepository, never()).flush();
    }

    @Test
    @DisplayName("입찰 금액이 null이면 INVALID_BID_AMOUNT")
    void executeBidTransaction_nullAmount_throwsInvalidBidAmount() {
        PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, null);

        assertThatThrownBy(() -> transactionService.executeBidTransaction(command))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_BID_AMOUNT);

        verify(auctionRepository, never()).findById(any());
        verify(bidRepository, never()).save(any());
    }

    @Test
    @DisplayName("시작 전 경매에는 입찰할 수 없다")
    void executeBidTransaction_beforeStart_throwsAuctionNotStarted() {
        Auction scheduledAuction = Auction.builder()
                .auctionId("A-001")
                .sellerId(10L)
                .productId(1L)
                .startPrice(100000L)
                .currentBid(500000L)
                .minIncrement(10000L)
                .bidCount(5)
                .status(AuctionStatus.LIVE)
                .startsAt(LocalDateTime.now().plusHours(1))
                .endsAt(LocalDateTime.now().plusHours(2))
                .build();
        given(auctionRepository.findById("A-001")).willReturn(Optional.of(scheduledAuction));

        assertThatThrownBy(() -> transactionService.executeBidTransaction(
                new PlaceBidCommand("A-001", 42L, 520000L)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.AUCTION_NOT_STARTED);

        verify(bidRepository, never()).save(any());
    }

    @Test
    @DisplayName("종료 시각이 지난 경매에는 상태가 LIVE여도 입찰할 수 없다")
    void executeBidTransaction_afterEndTime_throwsAuctionAlreadyEnded() {
        Auction expiredAuction = Auction.builder()
                .auctionId("A-001")
                .sellerId(10L)
                .productId(1L)
                .startPrice(100000L)
                .currentBid(500000L)
                .minIncrement(10000L)
                .bidCount(5)
                .status(AuctionStatus.LIVE)
                .startsAt(LocalDateTime.now().minusHours(2))
                .endsAt(LocalDateTime.now().minusHours(1))
                .build();
        given(auctionRepository.findById("A-001")).willReturn(Optional.of(expiredAuction));

        assertThatThrownBy(() -> transactionService.executeBidTransaction(
                new PlaceBidCommand("A-001", 42L, 520000L)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.AUCTION_ALREADY_ENDED);

        verify(bidRepository, never()).save(any());
    }

    @Test
    @DisplayName("flush에서 발생한 버전 충돌을 오케스트레이터로 전파한다")
    void executeBidTransaction_flushConflict_propagates() {
        PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 520000L);
        Bid savedBid = Bid.builder()
                .bidId(101L)
                .auctionId("A-001")
                .bidderId(42L)
                .amount(520000L)
                .build();
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException(Auction.class, "A-001");

        given(auctionRepository.findById("A-001")).willReturn(Optional.of(auction));
        given(bidRepository.save(any(Bid.class))).willReturn(savedBid);
        given(auctionRepository.save(auction)).willReturn(auction);
        org.mockito.BDDMockito.willThrow(conflict).given(auctionRepository).flush();

        assertThatThrownBy(() -> transactionService.executeBidTransaction(command))
                .isSameAs(conflict);
    }
}
