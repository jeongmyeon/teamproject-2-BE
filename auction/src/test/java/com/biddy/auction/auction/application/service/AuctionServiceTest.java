package com.biddy.auction.auction.application.service;

import com.biddy.auction.auction.application.dto.AuctionDetailResult;
import com.biddy.auction.auction.application.dto.AuctionFeedQuery;
import com.biddy.auction.auction.application.dto.AuctionFeedResult;
import com.biddy.auction.auction.application.dto.AuctionResultInfo;
import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.model.AuctionStatus;
import com.biddy.auction.auction.domain.repository.AuctionRepository;
import com.biddy.auction.auction.infra.kafka.ProductAuctionRegisteredPayload;
import com.biddy.auction.bid.domain.model.Bid;
import com.biddy.auction.bid.domain.repository.BidRepository;
import com.biddy.auction.common.exception.BusinessException;
import com.biddy.auction.common.exception.ErrorCode;
import com.biddy.auction.watch.infra.redis.WatchRedisRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuctionServiceTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private WatchRedisRepository watchRedis;

    @InjectMocks
    private AuctionService auctionService;

    private Auction createAuction(String id, String name, Long currentBid, Integer bidCount) {
        return Auction.builder()
                .auctionId(id)
                .sellerId(1L)
                .productId(1L)
                .startPrice(100000L)
                .minIncrement(10000L)
                .currentBid(currentBid)
                .currentBidderId(bidCount > 0 ? 42L : null)
                .bidCount(bidCount)
                .watcherCount(10)
                .endsAt(LocalDateTime.of(2026, 6, 20, 15, 0))
                .build();
    }

    private Bid createBid(String auctionId, Long bidderId, Long amount) {
        return Bid.builder()
                .bidId(1L)
                .auctionId(auctionId)
                .bidderId(bidderId)
                .amount(amount)
                .bidAt(LocalDateTime.of(2026, 6, 18, 14, 0))
                .build();
    }

    @Test
    @DisplayName("상품 경매 등록 이벤트의 가격 타입을 변환하여 경매를 생성한다")
    void createFromProduct_convertsPriceContractAndSavesAuction() {
        ProductAuctionRegisteredPayload payload = new ProductAuctionRegisteredPayload(
                10L,
                20L,
                new BigDecimal("5000.00"),
                500,
                LocalDateTime.of(2026, 7, 30, 10, 0),
                LocalDateTime.of(2026, 7, 31, 10, 0)
        );
        ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);

        auctionService.createFromProduct(payload);

        verify(auctionRepository).save(captor.capture());
        Auction savedAuction = captor.getValue();
        assertThat(savedAuction.getProductId()).isEqualTo(10L);
        assertThat(savedAuction.getSellerId()).isEqualTo(20L);
        assertThat(savedAuction.getStartPrice()).isEqualTo(5000L);
        assertThat(savedAuction.getCurrentBid()).isEqualTo(5000L);
        assertThat(savedAuction.getMinIncrement()).isEqualTo(500L);
    }

    @Nested
    @DisplayName("getAuctionFeed")
    class GetAuctionFeed {

        @Test
        @DisplayName("필터 없이 조회하면 전체 경매 피드를 반환한다")
        void withNoFilters_returnsAllAuctions() {
            AuctionFeedQuery query = new AuctionFeedQuery(null, null, 0, 20);
            List<Auction> auctions = List.of(
                    createAuction("A-001", "상품1", 500000L, 3),
                    createAuction("A-002", "상품2", 720000L, 6)
            );
            given(auctionRepository.findByFilters(eq(null), any(Pageable.class)))
                    .willReturn(new PageImpl<>(auctions));

            Page<AuctionFeedResult> result = auctionService.getAuctionFeed(query);

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).auctionId()).isEqualTo("A-001");
            assertThat(result.getContent().get(1).currentBid()).isEqualTo(720000L);
        }

        @Test
        @DisplayName("status=LIVE로 필터링하면 LIVE 경매만 조회한다")
        void withStatusFilter_filtersCorrectly() {
            AuctionFeedQuery query = new AuctionFeedQuery(AuctionStatus.LIVE, null, 0, 20);
            given(auctionRepository.findByFilters(eq(AuctionStatus.LIVE), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(createAuction("A-001", "상품1", 500000L, 3))));

            Page<AuctionFeedResult> result = auctionService.getAuctionFeed(query);

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("sort=ending이면 endsAt ASC로 정렬한다")
        void withSortEnding_sortsByEndsAtAsc() {
            AuctionFeedQuery query = new AuctionFeedQuery(null, "ending", 0, 20);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            given(auctionRepository.findByFilters(any(), captor.capture()))
                    .willReturn(Page.empty());

            auctionService.getAuctionFeed(query);

            Sort sort = captor.getValue().getSort();
            assertThat(sort.getOrderFor("endsAt")).isNotNull();
            assertThat(sort.getOrderFor("endsAt").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        @DisplayName("sort=price이면 currentBid DESC로 정렬한다")
        void withSortPrice_sortsByCurrentBidDesc() {
            AuctionFeedQuery query = new AuctionFeedQuery(null, "price", 0, 20);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            given(auctionRepository.findByFilters(any(), captor.capture()))
                    .willReturn(Page.empty());

            auctionService.getAuctionFeed(query);

            Sort sort = captor.getValue().getSort();
            assertThat(sort.getOrderFor("currentBid")).isNotNull();
            assertThat(sort.getOrderFor("currentBid").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("sort=null이면 createdAt DESC로 정렬한다")
        void withSortNull_sortsByCreatedAtDesc() {
            AuctionFeedQuery query = new AuctionFeedQuery(null, null, 0, 20);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            given(auctionRepository.findByFilters(any(), captor.capture()))
                    .willReturn(Page.empty());

            auctionService.getAuctionFeed(query);

            Sort sort = captor.getValue().getSort();
            assertThat(sort.getOrderFor("createdAt")).isNotNull();
            assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        @DisplayName("Auction을 AuctionFeedResult로 정확히 매핑한다")
        void mapsAuctionToResultCorrectly() {
            AuctionFeedQuery query = new AuctionFeedQuery(null, null, 0, 20);
            Auction auction = createAuction("A-FNF97", "나이키 덩크", 720000L, 6);
            given(auctionRepository.findByFilters(any(), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(auction)));

            AuctionFeedResult item = auctionService.getAuctionFeed(query).getContent().get(0);

            assertThat(item.auctionId()).isEqualTo("A-FNF97");
            assertThat(item.currentBid()).isEqualTo(720000L);
            assertThat(item.bidCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("페이지네이션 파라미터가 정확히 전달된다")
        void passesPageParametersCorrectly() {
            AuctionFeedQuery query = new AuctionFeedQuery(null, null, 2, 10);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            given(auctionRepository.findByFilters(any(), captor.capture()))
                    .willReturn(Page.empty());

            auctionService.getAuctionFeed(query);

            assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
            assertThat(captor.getValue().getPageSize()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("getAuctionDetail")
    class GetAuctionDetail {

        @Test
        @DisplayName("존재하는 경매를 상세 조회하면 정상 결과를 반환한다")
        void withExistingAuction_returnsDetail() {
            Auction auction = createAuction("A-001", "나이키 덩크", 720000L, 6);
            given(auctionRepository.findById("A-001")).willReturn(Optional.of(auction));
            given(watchRedis.getCount("A-001")).willReturn(10);

            AuctionDetailResult result = auctionService.getAuctionDetail("A-001", null);

            assertThat(result.auctionId()).isEqualTo("A-001");
            assertThat(result.startPrice()).isEqualTo(100000L);
            assertThat(result.minIncrement()).isEqualTo(10000L);
            assertThat(result.currentBid()).isEqualTo(720000L);
            assertThat(result.bidCount()).isEqualTo(6);
            assertThat(result.status()).isEqualTo(AuctionStatus.LIVE);
            assertThat(result.watcherCount()).isEqualTo(10);
            assertThat(result.topBidder()).isNotNull();
            assertThat(result.topBidder().bidderId()).isEqualTo(42L);
            assertThat(result.topBidder().amount()).isEqualTo(720000L);
            verify(bidRepository, never()).findTopByAuctionId("A-001");
        }

        @Test
        @DisplayName("입찰이 없는 경매를 조회하면 topBidder가 null이다")
        void withNoBids_topBidderIsNull() {
            Auction auction = createAuction("A-002", "상품", 0L, 0);
            given(auctionRepository.findById("A-002")).willReturn(Optional.of(auction));

            AuctionDetailResult result = auctionService.getAuctionDetail("A-002", null);

            assertThat(result.topBidder()).isNull();
            assertThat(result.isWatching()).isFalse();
            assertThat(result.myHighestBid()).isNull();
            verify(bidRepository, never()).findTopByAuctionId("A-002");
        }

        @Test
        @DisplayName("존재하지 않는 경매를 조회하면 AUCTION_NOT_FOUND 예외가 발생한다")
        void withNonExistingAuction_throwsException() {
            given(auctionRepository.findById("INVALID")).willReturn(Optional.empty());

            assertThatThrownBy(() -> auctionService.getAuctionDetail("INVALID", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("getAuctionResult")
    class GetAuctionResult {

        private Auction createEndedAuction(String id, int bidCount) {
            Auction auction = createAuction(id, "상품", bidCount > 0 ? 720000L : 0L, bidCount);
            if (bidCount > 0) {
                auction.close(42L, 101L);
            } else {
                auction.closeUnsold();
            }
            return auction;
        }

        @Test
        @DisplayName("낙찰된 경매의 결과를 조회한다")
        void soldAuction_returnsSoldResult() {
            Auction auction = createEndedAuction("A-001", 5);
            Bid topBid = createBid("A-001", 42L, 720000L);
            given(auctionRepository.findById("A-001")).willReturn(Optional.of(auction));
            given(bidRepository.findTopByAuctionId("A-001")).willReturn(Optional.of(topBid));

            AuctionResultInfo result = auctionService.getAuctionResult("A-001");

            assertThat(result.type()).isEqualTo("SOLD");
            assertThat(result.winnerId()).isEqualTo(42L);
            assertThat(result.finalBid()).isEqualTo(720000L);
            assertThat(result.totalBids()).isEqualTo(5);
            assertThat(result.paymentDeadline()).isNotNull();
        }

        @Test
        @DisplayName("입찰 수가 있지만 입찰 행이 없으면 DATA_INTEGRITY_ERROR")
        void soldAuction_withoutBidRow_throwsDataIntegrityError() {
            Auction auction = createEndedAuction("A-001", 5);
            given(auctionRepository.findById("A-001")).willReturn(Optional.of(auction));
            given(bidRepository.findTopByAuctionId("A-001")).willReturn(Optional.empty());

            assertThatThrownBy(() -> auctionService.getAuctionResult("A-001"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DATA_INTEGRITY_ERROR);
        }

        @Test
        @DisplayName("유찰된 경매의 결과를 조회한다")
        void unsoldAuction_returnsUnsoldResult() {
            Auction auction = createEndedAuction("A-002", 0);
            given(auctionRepository.findById("A-002")).willReturn(Optional.of(auction));

            AuctionResultInfo result = auctionService.getAuctionResult("A-002");

            assertThat(result.type()).isEqualTo("UNSOLD");
            assertThat(result.winnerId()).isNull();
            assertThat(result.finalBid()).isNull();
            assertThat(result.totalBids()).isEqualTo(0);
            assertThat(result.paymentDeadline()).isNull();
        }

        @Test
        @DisplayName("LIVE 상태 경매를 조회하면 AUCTION_STILL_LIVE")
        void liveAuction_throwsStillLive() {
            Auction auction = createAuction("A-003", "상품", 500000L, 3);
            given(auctionRepository.findById("A-003")).willReturn(Optional.of(auction));

            assertThatThrownBy(() -> auctionService.getAuctionResult("A-003"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_STILL_LIVE);
        }

        @Test
        @DisplayName("존재하지 않는 경매를 조회하면 AUCTION_NOT_FOUND")
        void notFound_throwsNotFound() {
            given(auctionRepository.findById("A-999")).willReturn(Optional.empty());

            assertThatThrownBy(() -> auctionService.getAuctionResult("A-999"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_NOT_FOUND);
        }
    }
}
