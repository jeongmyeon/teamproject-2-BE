package com.biddy.auction.bid.application.service;

import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.repository.AuctionRepository;
import com.biddy.auction.auction.infra.websocket.AuctionWebSocketPublisher;
import com.biddy.auction.auction.domain.model.AuctionStatus;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 입찰 UseCase 구현체.
 *
 * <p>입찰 내역 조회와 입찰 실행을 담당한다.
 * 입찰 실행 시 비관적 락(Pessimistic Lock)을 사용하여
 * 동시 입찰 요청의 직렬화를 보장한다.</p>
 *
 * <p>입찰 플로우:
 * <ol>
 *   <li>1차 유효 검증 (락 없이 빠른 검증)</li>
 *   <li>비관적 락 획득 (SELECT ... FOR UPDATE)</li>
 *   <li>최종 유효 검증 (락 보유 상태에서 재검증)</li>
 *   <li>입찰 저장 + 현재가 갱신 (하나의 트랜잭션)</li>
 * </ol></p>
 */
@Service
@RequiredArgsConstructor
public class BidService implements BidUseCase {

    private final BidRepository bidRepository;
    private final AuctionRepository auctionRepository;
    private final AuctionWebSocketPublisher webSocketPublisher;

    /**
     * 특정 경매의 입찰 내역을 최신순으로 페이징 조회한다.
     *
     * @param query 조회 조건 (경매 ID, 페이징)
     * @return 입찰 내역 결과 페이지
     */
    @Override
    @Transactional(readOnly = true)
    public Page<BidHistoryResult> getBidHistory(BidHistoryQuery query) {
        PageRequest pageable = PageRequest.of(query.page(), query.size(),
                Sort.by(Sort.Direction.DESC, "bidAt"));

        return bidRepository.findByAuctionId(query.auctionId(), pageable)
                .map(BidHistoryResult::from);
    }

    /**
     * 입찰을 실행한다.
     *
     * <p>1차 검증 → 비관적 락 → 최종 검증 → 입찰 저장 → 현재가 갱신.
     * 전체 과정이 하나의 트랜잭션으로 실행되어 원자성을 보장한다.</p>
     *
     * @param command 입찰 요청 (경매 ID, 입찰자 ID, 금액)
     * @return 입찰 결과 (입찰 ID, 금액, 갱신된 현재가, 입찰 수)
     * @throws BusinessException 경매 미존재, 종료, 본인 입찰, 금액 부족 시
     */
    @Override
    @Transactional
    public PlaceBidResult placeBid(PlaceBidCommand command) {
        // 1. 1차 유효 검증 (락 없이 빠른 검증 — DB 락 대기 시간 절약)
        Auction auction = auctionRepository.findById(command.auctionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        validatePreLock(auction, command);

        // 2. 비관적 락 획득 (SELECT ... FOR UPDATE)
        Auction lockedAuction = auctionRepository.findByIdForUpdate(command.auctionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        // 3. 최종 유효 검증 (락 보유 상태에서 최신 DB 값 기반 재검증)
        validatePostLock(lockedAuction, command);

        // 4. 입찰 저장
        Bid bid = Bid.builder()
                .auctionId(command.auctionId())
                .bidderId(command.bidderId())
                .amount(command.amount())
                .build();

        Bid savedBid = bidRepository.save(bid);

        // 5. 경매 현재가, 현재 최고 입찰자와 입찰 수를 함께 갱신
        lockedAuction.applyBid(command.amount(), command.bidderId());
        auctionRepository.save(lockedAuction);

        // 6. WebSocket으로 경매 구독자에게 실시간 push
        webSocketPublisher.publishBid(
                command.auctionId(),
                lockedAuction.getCurrentBid(),
                lockedAuction.getBidCount(),
                command.bidderId()
        );

        return new PlaceBidResult(
                savedBid.getBidId(),
                savedBid.getAmount(),
                lockedAuction.getCurrentBid(),
                lockedAuction.getBidCount()
        );
    }

    /**
     * 1차 유효 검증 (Pre-Lock Validation).
     * 락 획득 전 빠르게 수행하여 불필요한 DB 락 대기를 줄인다.
     */
    private void validatePreLock(Auction auction, PlaceBidCommand command) {
        if (!auction.isLive()) {
            throw new BusinessException(ErrorCode.AUCTION_ALREADY_ENDED);
        }
        if (auction.getSellerId().equals(command.bidderId())) {
            throw new BusinessException(ErrorCode.SELF_BID_NOT_ALLOWED);
        }
        if (command.amount() < auction.getCurrentBid() + auction.getMinIncrement()) {
            throw new BusinessException(ErrorCode.BID_AMOUNT_TOO_LOW);
        }
    }

    /**
     * 최종 유효 검증 (Post-Lock Validation).
     * 락을 잡은 상태에서 최신 DB 값 기반으로 재검증한다.
     * 1차 검증과 락 획득 사이에 다른 트랜잭션이 값을 변경했을 수 있다.
     */
    private void validatePostLock(Auction auction, PlaceBidCommand command) {
        if (!auction.isLive()) {
            throw new BusinessException(ErrorCode.AUCTION_ALREADY_ENDED);
        }
        if (command.amount() < auction.getCurrentBid() + auction.getMinIncrement()) {
            throw new BusinessException(ErrorCode.BID_AMOUNT_TOO_LOW);
        }
    }

    /**
     * 내가 입찰에 참여한 경매 목록을 조회한다.
     *
     * <p>bid 테이블에서 입찰한 고유 경매 ID를 추출하고,
     * 각 경매의 현재 상태와 내 최고 입찰 금액, 최고 입찰자 여부를 조합한다.</p>
     *
     * @param bidderId 입찰자 회원 ID
     * @param status   경매 상태 필터 (null이면 전체)
     * @param page     페이지 번호
     * @param size     페이지 크기
     * @return 내 입찰 참여 경매 목록
     */
    @Override
    @Transactional(readOnly = true)
    public Page<MyBidResult> getMyBids(Long bidderId, AuctionStatus status, int page, int size) {
        List<String> auctionIds = bidRepository.findDistinctAuctionIdsByBidderId(bidderId);

        List<MyBidResult> results = auctionIds.stream()
                .map(auctionId -> auctionRepository.findById(auctionId).orElse(null))
                .filter(auction -> auction != null)
                .filter(auction -> status == null || auction.getStatus() == status)
                .map(auction -> {
                    Bid myTopBid = bidRepository.findTopByAuctionIdAndBidderId(auction.getAuctionId(), bidderId)
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
