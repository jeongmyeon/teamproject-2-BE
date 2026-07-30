package com.biddy.auction.bid.application.usecase;

import com.biddy.auction.bid.application.dto.BidHistoryQuery;
import com.biddy.auction.bid.application.dto.BidHistoryResult;
import com.biddy.auction.bid.application.dto.MyBidResult;
import com.biddy.auction.bid.application.dto.PlaceBidCommand;
import com.biddy.auction.bid.application.dto.PlaceBidResult;
import com.biddy.auction.auction.domain.model.AuctionStatus;
import org.springframework.data.domain.Page;

/**
 * 입찰 UseCase 인터페이스.
 *
 * <p>입찰 도메인의 비즈니스 행위를 정의한다.
 * Controller는 이 인터페이스에만 의존하고, 구현체(Service)는 모른다.</p>
 */
public interface BidUseCase {

    /** 입찰 내역 페이징 조회 */
    Page<BidHistoryResult> getBidHistory(BidHistoryQuery query);

    /**
     * 입찰을 실행한다.
     *
     * <p>비관적 쓰기 락 획득 → 최신 상태 검증 → 입찰 저장 → 현재가 갱신</p>
     *
     * @param command 입찰 요청 (경매 ID, 입찰자 ID, 금액)
     * @return 입찰 결과 (입찰 ID, 금액, 갱신된 현재가, 입찰 수)
     */
    PlaceBidResult placeBid(PlaceBidCommand command);

    /**
     * 내가 입찰에 참여한 경매 목록을 조회한다.
     *
     * @param bidderId 입찰자 회원 ID
     * @param status   경매 상태 필터 (null이면 전체)
     * @param page     페이지 번호
     * @param size     페이지 크기
     * @return 내 입찰 참여 경매 목록
     */
    Page<MyBidResult> getMyBids(Long bidderId, AuctionStatus status, int page, int size);
}
