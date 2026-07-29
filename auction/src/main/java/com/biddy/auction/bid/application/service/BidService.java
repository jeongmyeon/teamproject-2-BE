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
import com.biddy.auction.common.exception.BusinessException;
import com.biddy.auction.common.exception.ErrorCode;
import com.biddy.auction.common.metrics.OptimisticLockMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 낙관적 락 기반 입찰 오케스트레이터.
 *
 * <p>이 빈은 트랜잭션을 열지 않는다. 각 재시도는 별도 빈인
 * {@link BidTransactionService}의 REQUIRES_NEW 트랜잭션에서 실행된다.</p>
 */
@Service
@Slf4j
public class BidService implements BidUseCase {

    private static final int MAX_ATTEMPTS = 3;

    private final BidRepository bidRepository;
    private final AuctionRepository auctionRepository;
    private final AuctionWebSocketPublisher webSocketPublisher;
    private final OptimisticLockMetrics metrics;
    private final BidTransactionService bidTransactionService;
    private final RetryTemplate optimisticLockRetryTemplate;

    public BidService(
            BidRepository bidRepository,
            AuctionRepository auctionRepository,
            AuctionWebSocketPublisher webSocketPublisher,
            OptimisticLockMetrics metrics,
            BidTransactionService bidTransactionService,
            @Qualifier("optimisticLockRetryTemplate") RetryTemplate optimisticLockRetryTemplate
    ) {
        this.bidRepository = bidRepository;
        this.auctionRepository = auctionRepository;
        this.webSocketPublisher = webSocketPublisher;
        this.metrics = metrics;
        this.bidTransactionService = bidTransactionService;
        this.optimisticLockRetryTemplate = optimisticLockRetryTemplate;
    }

    /**
     * 낙관적 락 충돌만 최대 3회 시도한다.
     * 업무 규칙 위반은 즉시 전파하고 큐로 전환하지 않는다.
     */
    @Override
    public PlaceBidResult placeBid(PlaceBidCommand command) {
        Timer.Sample sample = metrics.startBidProcessing();
        long startedAt = System.nanoTime();
        int[] attempts = {1};

        try {
            PlaceBidResult result = optimisticLockRetryTemplate.execute(context -> {
                int attempt = context.getRetryCount() + 1;
                attempts[0] = attempt;

                if (attempt > 1) {
                    metrics.recordRetryAttempt(command.auctionId(), attempt);
                }

                try {
                    return bidTransactionService.executeBidTransaction(command);
                } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
                    metrics.recordOptimisticLockConflict(command.auctionId());
                    log.warn("낙관적 락 충돌 - 경매: {}, 시도: {}/{}",
                            command.auctionId(), attempt, MAX_ATTEMPTS);
                    throw exception;
                }
            });

            if (attempts[0] > 1) {
                metrics.recordRetrySuccess(
                        command.auctionId(),
                        attempts[0],
                        Duration.ofNanos(System.nanoTime() - startedAt)
                );
            }

            publishCommittedBid(command, result);

            log.info("입찰 커밋 성공 - 경매: {}, 입찰ID: {}, 금액: {}원, 시도: {}",
                    command.auctionId(), result.bidId(), result.amount(), attempts[0]);
            return result;
        } catch (BusinessException exception) {
            // 업무 예외는 재시도 또는 큐 전환 없이 원래 HTTP 상태로 응답한다.
            throw exception;
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            metrics.recordRetryFailure(command.auctionId(), MAX_ATTEMPTS);
            throw new BusinessException(
                    ErrorCode.BID_CONCURRENT_MODIFICATION,
                    "동시 입찰 충돌이 발생했습니다. 최신 가격을 확인한 후 다시 시도해주세요"
            );
        } finally {
            metrics.recordBidProcessing(sample);
        }
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
