package com.biddy.auction.bid.application.service;

import com.biddy.auction.auction.domain.repository.AuctionRepository;
import com.biddy.auction.auction.infra.websocket.AuctionWebSocketPublisher;
import com.biddy.auction.bid.application.dto.PlaceBidCommand;
import com.biddy.auction.bid.application.dto.PlaceBidResult;
import com.biddy.auction.bid.domain.repository.BidRepository;
import com.biddy.auction.common.exception.BusinessException;
import com.biddy.auction.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BidServicePessimisticLockTest {

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionWebSocketPublisher webSocketPublisher;

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
                bidTransactionService
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
    }

    @Test
    @DisplayName("업무 예외는 재시도 없이 그대로 전파한다")
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
