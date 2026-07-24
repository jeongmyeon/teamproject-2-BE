package com.biddy.auction.auction.application.service;

import com.biddy.auction.auction.application.dto.AuctionDetailResult;
import com.biddy.auction.auction.application.dto.AuctionFeedQuery;
import com.biddy.auction.auction.application.dto.AuctionFeedResult;
import com.biddy.auction.auction.application.dto.AuctionResultInfo;
import com.biddy.auction.auction.application.usecase.AuctionUseCase;
import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.repository.AuctionRepository;
import com.biddy.auction.auction.infra.kafka.AuctionEndedEventProducer;
import com.biddy.auction.auction.infra.kafka.ProductAuctionRegisteredPayload;
import com.biddy.auction.auction.infra.websocket.AuctionWebSocketPublisher;
import com.biddy.auction.bid.domain.model.Bid;
import com.biddy.auction.bid.domain.repository.BidRepository;
import com.biddy.auction.common.exception.BusinessException;
import com.biddy.auction.common.exception.ErrorCode;
import com.biddy.auction.watch.infra.redis.WatchRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import com.biddy.auction.auction.domain.model.AuctionStatus;

/**
 * 경매 UseCase 구현체.
 *
 * <p>경매 피드 조회, 상세 조회, Kafka 이벤트 기반 경매 생성 등
 * 경매 관련 비즈니스 로직을 처리한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuctionService implements AuctionUseCase {

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final WatchRedisRepository watchRedis;
    private final AuctionWebSocketPublisher webSocketPublisher;
    private final AuctionEndedEventProducer auctionEndedEventProducer;

    /**
     * 조건에 맞는 경매 피드를 페이징 조회한다.
     *
     * <p>정렬 문자열을 Spring Sort로 변환 후 Repository에 위임하고,
     * Auction Entity를 AuctionFeedResult DTO로 매핑하여 반환한다.</p>
     *
     * @param query 조회 조건 (상태, 카테고리, 정렬, 페이징)
     * @return 경매 피드 결과 페이지
     */
    @Override
    @Transactional(readOnly = true)
    public Page<AuctionFeedResult> getAuctionFeed(AuctionFeedQuery query) {
        Sort sort = resolveSort(query.sort());
        Pageable pageable = PageRequest.of(query.page(), query.size(), sort);

        // 마감임박 정렬 시 종료된 경매 제외
        AuctionStatus statusFilter = "ending".equals(query.sort()) && query.status() == null
                ? AuctionStatus.LIVE
                : query.status();

        return auctionRepository.findByFilters(statusFilter, pageable)
                .map(auction -> AuctionFeedResult.from(auction, watchRedis.getCount(auction.getAuctionId())));
    }

    /**
     * 경매 상세 정보를 조회한다.
     *
     * <p>경매 엔티티에 저장된 현재가와 현재 최고 입찰자를 반환한다.
     * 경매가 존재하지 않으면 {@code AUCTION_NOT_FOUND} 예외를 발생시킨다.</p>
     *
     * @param auctionId 경매 ID
     * @return 경매 상세 결과 (상품정보, 가격, 통계, 최고입찰자 포함)
     * @throws BusinessException 경매를 찾을 수 없는 경우
     */
    @Override
    @Transactional(readOnly = true)
    public AuctionDetailResult getAuctionDetail(String auctionId, Long memberId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        boolean isWatching = memberId != null && watchRedis.isWatching(memberId, auctionId);
        int watcherCount = watchRedis.getCount(auctionId);

        Long myHighestBid = memberId != null
                ? bidRepository.findTopByAuctionIdAndBidderId(auctionId, memberId)
                        .map(Bid::getAmount).orElse(null)
                : null;

        return AuctionDetailResult.from(auction, isWatching, watcherCount, myHighestBid);
    }

    /**
     * 낙찰/유찰 결과를 조회한다.
     *
     * <p>LIVE 상태면 AUCTION_STILL_LIVE 예외를 발생시킨다.
     * ENDED 상태에서 입찰 유무에 따라 SOLD/UNSOLD를 반환한다.</p>
     *
     * @param auctionId 경매 ID
     * @return 낙찰(SOLD) 또는 유찰(UNSOLD) 결과
     */
    @Override
    @Transactional(readOnly = true)
    public AuctionResultInfo getAuctionResult(String auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        if (auction.isLive()) {
            throw new BusinessException(ErrorCode.AUCTION_STILL_LIVE);
        }

        if (auction.hasBids()) {
            Bid topBid = bidRepository.findTopByAuctionId(auctionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.BID_NOT_FOUND));
            return AuctionResultInfo.sold(auction, topBid);
        }

        return AuctionResultInfo.unsold(auction);
    }

    /**
     * Product Service의 경매 상품 등록 이벤트로부터 경매를 자동 생성한다.
     *
     * <p>토픽: {@code product.auction.registered}
     * Product가 데이터의 주인(Single Source of Truth)이며,
     * Auction은 이벤트에서 비정규화 복사만 수행한다.</p>
     *
     * @param payload 경매 상품 등록 이벤트 데이터
     */
    @Transactional
    public void createFromProduct(ProductAuctionRegisteredPayload payload) {
        String auctionId = generateAuctionId();

        if (auctionRepository.existsById(auctionId)) {
            log.warn("경매 이미 존재: auctionId={}", auctionId);
            return;
        }

        Auction auction = Auction.builder()
                .auctionId(auctionId)
                .productId(payload.productId())
                .sellerId(payload.sellerId())
                .startPrice(payload.startPrice())
                .currentBid(payload.startPrice())
                .minIncrement(payload.minIncrement())
                .startsAt(payload.startsAt())
                .endsAt(payload.endsAt())
                .build();

        auctionRepository.save(auction);
        log.info("경매 자동 생성: auctionId={}, productId={}",
                auctionId, payload.productId());
    }

    @Override
    @Transactional
    public void closeAuctionBySeller(String auctionId, Long sellerId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        if (!auction.getSellerId().equals(sellerId)) {
            throw new BusinessException(ErrorCode.NOT_AUCTION_OWNER);
        }

        if (!auction.isLive()) {
            throw new BusinessException(ErrorCode.AUCTION_ALREADY_ENDED);
        }

        if (auction.hasBids()) {
            Bid topBid = bidRepository.findTopByAuctionId(auctionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.BID_NOT_FOUND));
            auction.close(topBid.getBidderId(), topBid.getBidId());
            webSocketPublisher.publishEnded(auctionId, topBid.getBidderId(), topBid.getAmount());
            auctionEndedEventProducer.publish(auction, topBid);
            log.info("판매자 즉시 종료 (낙찰): auctionId={}, winnerId={}", auctionId, topBid.getBidderId());
        } else {
            auction.closeUnsold();
            webSocketPublisher.publishUnsold(auctionId);
            log.info("판매자 즉시 종료 (유찰): auctionId={}", auctionId);
        }
    }

    /** 경매 ID 생성 (A- prefix + 랜덤 5자리 대문자 영숫자) */
    private String generateAuctionId() {
        return "A-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    }

    /**
     * 정렬 파라미터 문자열을 Spring Sort 객체로 변환한다.
     * - "ending" → 마감임박순 (endsAt ASC)
     * - "price"  → 높은 가격순 (currentBid DESC)
     * - "latest" 또는 null → 최신순 (createdAt DESC, 기본값)
     */
    private Sort resolveSort(String sort) {
        if (sort == null) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        return switch (sort) {
            case "ending" -> Sort.by(Sort.Direction.ASC, "endsAt");
            case "price" -> Sort.by(Sort.Direction.DESC, "currentBid");
            case "priceAsc" -> Sort.by(Sort.Direction.ASC, "currentBid");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }
}
