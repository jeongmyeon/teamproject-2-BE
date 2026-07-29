package com.biddy.auction.bid.application.service;

import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.repository.AuctionRepository;
import com.biddy.auction.auction.infra.websocket.AuctionWebSocketPublisher;
import com.biddy.auction.bid.application.dto.PlaceBidCommand;
import com.biddy.auction.bid.application.dto.PlaceBidResult;
import com.biddy.auction.bid.domain.repository.BidRepository;
import com.biddy.auction.common.config.OptimisticLockRetryConfig;
import com.biddy.auction.common.exception.BusinessException;
import com.biddy.auction.common.exception.ErrorCode;
import com.biddy.auction.common.metrics.OptimisticLockMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BidServiceOptimisticLockTest {

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionWebSocketPublisher webSocketPublisher;

    @Mock
    private OptimisticLockMetrics metrics;

    @Mock
    private BidTransactionService bidTransactionService;

    private BidService bidService;

    private final PlaceBidCommand command = new PlaceBidCommand("A-001", 42L, 520000L);
    private final PlaceBidResult committedResult = new PlaceBidResult(101L, 520000L, 520000L, 6);

    @BeforeEach
    void setUp() {
        bidService = new BidService(
                bidRepository,
                auctionRepository,
                webSocketPublisher,
                metrics,
                bidTransactionService,
                new OptimisticLockRetryConfig().optimisticLockRetryTemplate()
        );
    }

    @Test
    @DisplayName("커밋 성공 후 WebSocket을 한 번 발행한다")
    void placeBid_committed_publishesAfterTransaction() {
        given(bidTransactionService.executeBidTransaction(command)).willReturn(committedResult);

        PlaceBidResult result = bidService.placeBid(command);

        assertThat(result).isEqualTo(committedResult);
        verify(bidTransactionService).executeBidTransaction(command);
        verify(webSocketPublisher).publishBid("A-001", 520000L, 6, 42L);
        verify(metrics, never()).recordOptimisticLockConflict("A-001");
    }

    @Test
    @DisplayName("업무 예외는 재시도하거나 큐 결과로 바꾸지 않는다")
    void placeBid_businessException_propagatesImmediately() {
        BusinessException failure = new BusinessException(
                ErrorCode.BID_AMOUNT_TOO_LOW,
                "최소 입찰 금액: 530000원"
        );
        given(bidTransactionService.executeBidTransaction(command)).willThrow(failure);

        assertThatThrownBy(() -> bidService.placeBid(command))
                .isSameAs(failure);

        verify(bidTransactionService).executeBidTransaction(command);
        verify(webSocketPublisher, never()).publishBid(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong()
        );
        verify(metrics, never()).recordRetryAttempt("A-001", 2);
    }

    @Test
    @DisplayName("낙관적 락 충돌 후 새 트랜잭션으로 재시도하고 성공한다")
    void placeBid_optimisticConflict_retriesAndSucceeds() {
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException(Auction.class, "A-001");
        given(bidTransactionService.executeBidTransaction(command))
                .willThrow(conflict)
                .willReturn(committedResult);

        PlaceBidResult result = bidService.placeBid(command);

        assertThat(result).isEqualTo(committedResult);
        verify(bidTransactionService, times(2)).executeBidTransaction(command);
        verify(metrics).recordOptimisticLockConflict("A-001");
        verify(metrics).recordRetryAttempt("A-001", 2);
        verify(webSocketPublisher).publishBid("A-001", 520000L, 6, 42L);
    }

    @Test
    @DisplayName("세 번의 낙관적 락 충돌 후 409 업무 예외를 반환한다")
    void placeBid_optimisticConflictExhausted_returnsConflict() {
        ObjectOptimisticLockingFailureException conflict =
                new ObjectOptimisticLockingFailureException(Auction.class, "A-001");
        given(bidTransactionService.executeBidTransaction(command)).willThrow(conflict);

        assertThatThrownBy(() -> bidService.placeBid(command))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.BID_CONCURRENT_MODIFICATION);

        verify(bidTransactionService, times(3)).executeBidTransaction(command);
        verify(metrics, times(3)).recordOptimisticLockConflict("A-001");
        verify(metrics).recordRetryFailure("A-001", 3);
        verify(webSocketPublisher, never()).publishBid(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    @DisplayName("커밋 후 WebSocket 발행 실패는 입찰 성공을 되돌리지 않는다")
    void placeBid_webSocketFailure_returnsCommittedResult() {
        given(bidTransactionService.executeBidTransaction(command)).willReturn(committedResult);
        org.mockito.BDDMockito.willThrow(new RuntimeException("broker unavailable"))
                .given(webSocketPublisher)
                .publishBid("A-001", 520000L, 6, 42L);

        PlaceBidResult result = bidService.placeBid(command);

        assertThat(result).isEqualTo(committedResult);
        verify(bidTransactionService).executeBidTransaction(command);
    }
}
