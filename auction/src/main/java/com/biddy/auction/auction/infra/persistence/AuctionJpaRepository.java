package com.biddy.auction.auction.infra.persistence;

import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.model.AuctionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Auction JPA Repository (Spring Data JPA).
 *
 * <p>category 필터는 제거됨 — category는 Product Service 책임.
 * 경매 피드에서 카테고리 필터링이 필요하면 Gateway/BFF에서 Product + Auction을 조합한다.</p>
 */
public interface AuctionJpaRepository extends JpaRepository<Auction, String> {

    /**
     * 상태 필터 기반 경매 조회 JPQL.
     * status가 null이면 전체 조회.
     */
    @Query("""
            SELECT a FROM Auction a
            WHERE (:status IS NULL OR a.status = :status)
            """)
    Page<Auction> findByFilters(
            @Param("status") AuctionStatus status,
            Pageable pageable
    );

    // 비관적 락 메서드 제거 - 낙관적 락(@Version)으로 대체됨
    // @Lock(LockModeType.PESSIMISTIC_WRITE) 사용하지 않음

    /** 종료 시각이 지난 LIVE 상태 경매 목록 조회 (스케줄러용) */
    List<Auction> findAllByStatusAndEndsAtBefore(AuctionStatus status, LocalDateTime now);
}
