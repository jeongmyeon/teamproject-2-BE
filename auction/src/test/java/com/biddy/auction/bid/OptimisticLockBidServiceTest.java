package com.biddy.auction.bid;

import com.biddy.auction.auction.domain.model.Auction;
import com.biddy.auction.auction.domain.model.AuctionStatus;
import com.biddy.auction.auction.domain.repository.AuctionRepository;
import com.biddy.auction.bid.application.dto.PlaceBidCommand;
import com.biddy.auction.bid.application.dto.PlaceBidResult;
import com.biddy.auction.bid.application.service.BidService;
import com.biddy.auction.bid.domain.model.Bid;
import com.biddy.auction.bid.domain.repository.BidRepository;
import com.biddy.auction.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 낙관적 락 입찰 서비스 통합 테스트
 *
 * 동시 입찰 시나리오를 시뮬레이션하여
 * 낙관적 락의 정확성과 성능을 검증합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.jpa.show-sql=false",
    "logging.level.com.biddy.auction=DEBUG",
    "spring.cloud.config.enabled=false",
    "eureka.client.enabled=false"
})
public class OptimisticLockBidServiceTest {

    @Autowired
    private BidService bidService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private BidRepository bidRepository;

    private Auction testAuction;

    @BeforeEach
    void setUp() {
        // 테스트 경매 생성
        testAuction = Auction.builder()
            .auctionId("TEST-" + UUID.randomUUID().toString().substring(0, 12))
            .productId(System.nanoTime())
            .sellerId(1000L)
            .startPrice(10000L)
            .currentBid(10000L)
            .minIncrement(1000L)
            .status(AuctionStatus.LIVE)
            .endsAt(LocalDateTime.now().plusHours(1))
            .build();

        testAuction = auctionRepository.save(testAuction);
    }

    @Test
    @DisplayName("단일 입찰이 성공적으로 처리된다")
    void testSingleBidSuccess() {
        // Given
        PlaceBidCommand command = new PlaceBidCommand(
            testAuction.getAuctionId(),
            2000L, // 입찰자 ID
            11000L // 입찰 금액
        );

        // When
        PlaceBidResult result = bidService.placeBid(command);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.amount()).isEqualTo(11000L);
        assertThat(result.currentBid()).isEqualTo(11000L);
        assertThat(result.bidCount()).isEqualTo(1);

        // DB 확인
        Auction updatedAuction = auctionRepository.findById(testAuction.getAuctionId()).orElseThrow();
        assertThat(updatedAuction.getCurrentBid()).isEqualTo(11000L);
        assertThat(updatedAuction.getCurrentBidderId()).isEqualTo(2000L);
        assertThat(updatedAuction.getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("동시 입찰 시 낙관적 락이 정확히 작동한다")
    void testConcurrentBidsWithOptimisticLock() throws InterruptedException, ExecutionException {
        // Given
        int threadCount = 10;
        int bidsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When
        List<Future<List<BidResult>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            Future<List<BidResult>> future = executor.submit(() -> {
                List<BidResult> results = new ArrayList<>();

                try {
                    startLatch.await(); // 모든 스레드 동시 시작
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                for (int j = 0; j < bidsPerThread; j++) {
                    Long bidderId = 2000L + threadNum;
                    Long amount = 11000L + (threadNum * bidsPerThread + j) * 1000L;

                    PlaceBidCommand command = new PlaceBidCommand(
                        testAuction.getAuctionId(),
                        bidderId,
                        amount
                    );

                    try {
                        PlaceBidResult result = bidService.placeBid(command);
                        results.add(new BidResult(true, amount, result.currentBid()));
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        results.add(new BidResult(false, amount, null));
                        failCount.incrementAndGet();
                    }
                }

                return results;
            });

            futures.add(future);
        }

        // 모든 스레드 동시 시작
        startLatch.countDown();

        // 결과 수집
        List<BidResult> allResults = new ArrayList<>();
        for (Future<List<BidResult>> future : futures) {
            allResults.addAll(future.get());
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then
        System.out.println("Success: " + successCount.get());
        System.out.println("Failed: " + failCount.get());

        // 최종 경매 상태 확인
        Auction finalAuction = auctionRepository.findById(testAuction.getAuctionId()).orElseThrow();

        // 최소 일부는 성공해야 함
        assertThat(successCount.get()).isGreaterThan(0);

        // 현재가가 올바르게 업데이트되었는지
        assertThat(finalAuction.getCurrentBid()).isGreaterThan(10000L);

        // 입찰 수가 정확한지
        assertThat(finalAuction.getBidCount()).isEqualTo(successCount.get());

        // DB에 저장된 입찰 수와 일치하는지
        List<Bid> allBids = bidRepository.findByAuctionId(
                testAuction.getAuctionId(),
                PageRequest.of(0, threadCount * bidsPerThread + 1)
        ).getContent();
        assertThat(allBids.size()).isEqualTo(successCount.get());

        // 데이터 정합성 검증: 가장 높은 입찰이 현재가와 일치
        Bid highestBid = allBids.stream()
            .max((a, b) -> a.getAmount().compareTo(b.getAmount()))
            .orElseThrow();
        assertThat(finalAuction.getCurrentBid()).isEqualTo(highestBid.getAmount());
    }

    @Test
    @DisplayName("판매자는 자신의 경매에 입찰할 수 없다")
    void testSellerCannotBid() {
        // Given
        PlaceBidCommand command = new PlaceBidCommand(
            testAuction.getAuctionId(),
            1000L, // 판매자 ID
            11000L
        );

        // When & Then
        assertThrows(BusinessException.class, () -> {
            bidService.placeBid(command);
        });
    }

    @Test
    @DisplayName("최소 입찰 금액보다 낮으면 실패한다")
    void testBidAmountTooLow() {
        // Given
        PlaceBidCommand command = new PlaceBidCommand(
            testAuction.getAuctionId(),
            2000L,
            10500L // 최소 증가액(1000) 미만
        );

        // When & Then
        assertThrows(BusinessException.class, () -> {
            bidService.placeBid(command);
        });
    }

    @Test
    @DisplayName("종료된 경매에는 입찰할 수 없다")
    void testCannotBidOnEndedAuction() {
        // Given
        testAuction.close(null, null);
        auctionRepository.save(testAuction);

        PlaceBidCommand command = new PlaceBidCommand(
            testAuction.getAuctionId(),
            2000L,
            11000L
        );

        // When & Then
        assertThrows(BusinessException.class, () -> {
            bidService.placeBid(command);
        });
    }

    @Test
    @DisplayName("높은 부하에서도 데이터 정합성이 유지된다")
    void testHighLoadDataConsistency() throws InterruptedException, ExecutionException {
        // Given
        int threadCount = 50;
        int totalBids = 500;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger bidCounter = new AtomicInteger(0);

        // When
        List<Future<BidStatistics>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;

            Future<BidStatistics> future = executor.submit(() -> {
                BidStatistics stats = new BidStatistics();

                try {
                    barrier.await(); // 모든 스레드 동시 시작
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }

                int sequence;
                while ((sequence = bidCounter.incrementAndGet()) <= totalBids) {
                    Long bidderId = 2000L + threadId;
                    Long amount = 11000L + sequence * 100L;

                    PlaceBidCommand command = new PlaceBidCommand(
                        testAuction.getAuctionId(),
                        bidderId,
                        amount
                    );

                    long startTime = System.currentTimeMillis();

                    try {
                        bidService.placeBid(command);
                        stats.successCount++;
                        stats.totalTime += (System.currentTimeMillis() - startTime);
                    } catch (BusinessException e) {
                        stats.businessErrorCount++;
                    } catch (Exception e) {
                        stats.retryExhaustedCount++;
                    }
                }

                return stats;
            });

            futures.add(future);
        }

        // 결과 수집
        BidStatistics totalStats = new BidStatistics();
        for (Future<BidStatistics> future : futures) {
            BidStatistics stats = future.get();
            totalStats.merge(stats);
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // Then
        System.out.println("=== High Load Test Results ===");
        System.out.println("Total Success: " + totalStats.successCount);
        System.out.println("Business Errors: " + totalStats.businessErrorCount);
        System.out.println("Retry Exhausted: " + totalStats.retryExhaustedCount);
        System.out.println("Avg Response Time: " +
            (totalStats.successCount > 0 ? totalStats.totalTime / totalStats.successCount : 0) + "ms");

        // 데이터 정합성 검증
        Auction finalAuction = auctionRepository.findById(testAuction.getAuctionId()).orElseThrow();
        List<Bid> allBids = bidRepository.findByAuctionId(
                testAuction.getAuctionId(),
                PageRequest.of(0, totalBids + 1)
        ).getContent();

        // 성공한 입찰 수와 DB 입찰 수가 일치
        assertThat(allBids.size()).isEqualTo(totalStats.successCount);

        // 경매의 입찰 수와 일치
        assertThat(finalAuction.getBidCount()).isEqualTo(totalStats.successCount);

        // 새 경매는 성공한 Auction UPDATE 수와 version이 일치
        assertThat(finalAuction.getVersion()).isEqualTo((long) totalStats.successCount);

        // 모든 입찰 금액이 고유함 (중복 없음)
        List<Long> amounts = allBids.stream()
            .map(Bid::getAmount)
            .distinct()
            .toList();
        assertThat(amounts.size()).isEqualTo(allBids.size());
    }

    // 헬퍼 클래스
    private record BidResult(boolean success, Long amount, Long currentBid) {}

    private static class BidStatistics {
        int successCount = 0;
        int businessErrorCount = 0;
        int retryExhaustedCount = 0;
        long totalTime = 0;

        void merge(BidStatistics other) {
            this.successCount += other.successCount;
            this.businessErrorCount += other.businessErrorCount;
            this.retryExhaustedCount += other.retryExhaustedCount;
            this.totalTime += other.totalTime;
        }
    }
}
