package com.biddy.auction.bid.application.service;

import com.biddy.auction.auction.domain.model.AuctionStatus;
import com.biddy.auction.auction.domain.repository.AuctionRepository;
import com.biddy.auction.auction.infra.websocket.AuctionWebSocketPublisher;
import com.biddy.auction.bid.application.dto.BidHistoryQuery;
import com.biddy.auction.bid.application.dto.BidHistoryResult;
import com.biddy.auction.bid.application.dto.MyBidResult;
import com.biddy.auction.bid.application.dto.PlaceBidCommand;
import com.biddy.auction.bid.application.dto.PlaceBidResult;
import com.biddy.auction.bid.application.usecase.BidUseCase;
import com.biddy.auction.bid.domain.model.Bid;
import com.biddy.auction.bid.domain.repository.BidRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 비관적 락 기반 입찰 오케스트레이터.
 *
 * <p>실제 입찰은 {@link BidTransactionService}가 새 트랜잭션에서 경매 행의
 * 비관적 쓰기 락을 획득한 뒤 처리한다.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BidService implements BidUseCase {

    private final BidRepository bidRepository;
    private final AuctionRepository auctionRepository;
    private final AuctionWebSocketPublisher webSocketPublisher;
    private final BidTransactionService bidTransactionService;

    /**
     * 비관적 락 트랜잭션을 한 번 실행하고 커밋된 결과만 반환한다.
     */
    @Override
    public PlaceBidResult placeBid(PlaceBidCommand command) {
        PlaceBidResult result = bidTransactionService.executeBidTransaction(command);
        publishCommittedBid(command, result);

        log.info("비관적 락 입찰 커밋 성공 - 경매: {}, 입찰ID: {}, 금액: {}원",
                command.auctionId(), result.bidId(), result.amount());
        return result;
    }

    /**
     * 트랜잭션 프록시가 commit을 마친 뒤 WebSocket을 발행한다.
     * 발행 실패가 이미 커밋된 입찰 응답을 500으로 바꾸지 않도록 격리한다.
     */
    private void publishCommittedBid(PlaceBidCommand command, PlaceBidResult result) {
        try {
            webSocketPublisher.publishBid(
                    command.auctionId(),
                    result.currentBid(),
                    result.bidCount(),
                    command.bidderId()
            );
        } catch (RuntimeException exception) {
            log.error("커밋 후 WebSocket 발행 실패 - 경매: {}, 입찰ID: {}",
                    command.auctionId(), result.bidId(), exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BidHistoryResult> getBidHistory(BidHistoryQuery query) {
        PageRequest pageable = PageRequest.of(
                query.page(),
                query.size(),
                Sort.by(Sort.Direction.DESC, "bidAt")
        );

        return bidRepository.findByAuctionId(query.auctionId(), pageable)
                .map(BidHistoryResult::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MyBidResult> getMyBids(Long bidderId, AuctionStatus status, int page, int size) {
        List<String> auctionIds = bidRepository.findDistinctAuctionIdsByBidderId(bidderId);

        List<MyBidResult> results = auctionIds.stream()
                .map(auctionId -> auctionRepository.findById(auctionId).orElse(null))
                .filter(auction -> auction != null)
                .filter(auction -> status == null || auction.getStatus() == status)
                .map(auction -> {
                    Bid myTopBid = bidRepository
                            .findTopByAuctionIdAndBidderId(auction.getAuctionId(), bidderId)
                            .orElse(null);
                    Bid topBid = bidRepository.findTopByAuctionId(auction.getAuctionId())
                            .orElse(null);

                    Long myHighestBid = myTopBid != null ? myTopBid.getAmount() : null;
                    boolean isTopBidder = topBid != null && topBid.getBidderId().equals(bidderId);

                    return new MyBidResult(
                            auction.getAuctionId(),
                            auction.getProductId(),
                            auction.getStatus().name(),
                            auction.getCurrentBid(),
                            auction.getEndsAt(),
                            myHighestBid,
                            isTopBidder,
                            auction.getBidCount()
                    );
                })
                .toList();

        int start = Math.min(page * size, results.size());
        int end = Math.min(start + size, results.size());
        return new PageImpl<>(results.subList(start, end), PageRequest.of(page, size), results.size());
    }
}
