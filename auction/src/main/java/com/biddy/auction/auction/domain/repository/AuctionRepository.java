package com.biddy.auction.auction.domain.repository;

import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.model.AuctionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 경매 Repository Port (도메인 레이어 인터페이스).
 *
 * <p>도메인이 필요로 하는 데이터 접근 규격을 정의한다.
 * 실제 구현은 infrastructure 레이어의 {@code AuctionRepositoryImpl}이 담당한다.
 * 이를 통해 도메인이 JPA/DB 기술에 의존하지 않는다 (DIP).</p>
 */
public interface AuctionRepository {

    /**
     * 상태 필터 기반 경매 목록 조회.
     * category는 Product Service 책임이므로 Auction에서 필터링하지 않는다.
     *
     * @param status   경매 상태 필터 (null이면 전체)
     * @param pageable 페이징 및 정렬 정보
     * @return 필터링된 경매 페이지
     */
    Page<Auction> findByFilters(AuctionStatus status, Pageable pageable);

    /**
     * 경매 ID로 단건 조회.
     *
     * @param auctionId 경매 ID
     * @return 경매 엔티티 (없으면 empty)
     */
    Optional<Auction> findById(String auctionId);

    // 비관적 락 메서드 제거 - 낙관적 락(@Version)으로 대체됨
    // findByIdForUpdate 메서드는 더 이상 사용하지 않음

    /**
     * 경매를 저장한다.
     *
     * @param auction 경매 엔티티
     * @return 저장된 경매 엔티티
     */
    Auction save(Auction auction);

    /**
     * 현재 트랜잭션의 변경 사항을 DB에 즉시 반영한다.
     * 낙관적 락 충돌을 성공 응답 전에 확정할 때 사용한다.
     */
    void flush();

    /**
     * 경매 ID 존재 여부를 확인한다 (멱등성 체크용).
     *
     * @param auctionId 경매 ID
     * @return 존재 여부
     */
    boolean existsById(String auctionId);

    /**
     * 종료 시각이 지난 LIVE 상태 경매 목록을 조회한다.
     * 스케줄러에서 종료 대상 수집에 사용한다.
     *
     * @param now 현재 시각
     * @return 종료 대상 경매 목록
     */
    List<Auction> findExpiredLiveAuctions(LocalDateTime now);
}
