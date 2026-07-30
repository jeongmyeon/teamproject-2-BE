package com.biddy.auction.auction.domain.model;

import com.biddy.auction.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 경매 도메인 엔티티.
 *
 * <p>경매의 핵심 비즈니스 상태를 관리한다.
 * 상품 정보(name, brand 등)는 Product Service 책임이며,
 * Auction은 {@code productId}만 참조하여 도메인 순수성을 유지한다.</p>
 *
 * <p>경매 피드/상세 화면에서 상품 정보가 필요하면
 * API Gateway/BFF에서 Product + Auction 응답을 조합한다.</p>
 *
 * @see AuctionStatus 경매 상태 (LIVE, ENDED)
 * @see BaseEntity 공통 Auditing 필드
 */
@Entity
@Table(name = "auction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Auction extends BaseEntity {

    @Id
    @Column(name = "auction_id", length = 20)
    private String auctionId;

    /** Product Service 상품 ID (참조용, FK 없음) */
    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    /** 판매자 회원 ID (Member Service 참조) */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    // -- 가격 정보 --

    /** 경매 시작 가격 */
    @Column(name = "start_price", nullable = false)
    private Long startPrice;

    /** 최소 입찰 단위 (입찰 금액 >= currentBid + minIncrement) */
    @Column(name = "min_increment", nullable = false)
    private Long minIncrement;

    /** 현재 최고 입찰가 (입찰 시 갱신) */
    @Builder.Default
    @Column(name = "current_bid", nullable = false)
    private Long currentBid = 0L;

    /** 현재 최고 입찰자 회원 ID (입찰이 없으면 null) */
    @Column(name = "current_bidder_id")
    private Long currentBidderId;

    // -- 통계 --

    @Builder.Default
    @Column(name = "bid_count", nullable = false)
    private Integer bidCount = 0;

    @Builder.Default
    @Column(name = "watcher_count", nullable = false)
    private Integer watcherCount = 0;

    // -- 상태 및 시간 --

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private AuctionStatus status = AuctionStatus.LIVE;

    /** 경매 시작 시각 (예약 경매 지원) */
    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    /** 경매 종료 시각 (스케줄러가 이 시각 이후 종료 처리) */
    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    // -- 낙찰 결과 --

    /** 낙찰자 회원 ID (종료 후 확정) */
    @Column(name = "winner_id")
    private Long winnerId;

    /** 낙찰 입찰 ID (종료 후 확정) */
    @Column(name = "winning_bid_id")
    private Long winningBidId;

    // -- 락 A/B 테스트 호환 컬럼 --

    /**
     * 낙관적 락 복구 시 다시 {@code @Version}으로 사용할 컬럼.
     * 비관적 락 variant에서는 일반 컬럼으로 매핑해 이중 락을 방지한다.
     */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    // -- 도메인 메서드 --

    /** 경매가 현재 진행 중인지 확인한다. */
    public boolean isLive() {
        return this.status == AuctionStatus.LIVE;
    }

    /**
     * 입찰을 적용한다.
     * 현재가, 현재 최고 입찰자와 입찰 수를 함께 갱신한다.
     *
     * @param bidAmount 입찰 금액
     * @param bidderId 입찰자 회원 ID
     */
    public void applyBid(Long bidAmount, Long bidderId) {
        this.currentBid = bidAmount;
        this.currentBidderId = bidderId;
        this.bidCount++;
    }

    /**
     * 경매를 종료하고 낙찰자를 확정한다.
     *
     * @param winnerId 낙찰자 회원 ID (유찰 시 null)
     * @param winningBidId 낙찰 입찰 ID (유찰 시 null)
     */
    public void close(Long winnerId, Long winningBidId) {
        this.status = AuctionStatus.ENDED;
        this.winnerId = winnerId;
        this.winningBidId = winningBidId;
    }

    /** 경매를 유찰 종료한다. */
    public void closeUnsold() {
        this.status = AuctionStatus.ENDED;
    }

    /** 입찰 내역이 존재하는지 확인한다. */
    public boolean hasBids() {
        return this.bidCount > 0;
    }

    /** 관심 등록 수를 1 증가시킨다. */
    public void incrementWatcherCount() {
        this.watcherCount++;
    }

    /** 관심 등록 수를 1 감소시킨다 (0 미만 방지). */
    public void decrementWatcherCount() {
        if (this.watcherCount > 0) {
            this.watcherCount--;
        }
    }
}
