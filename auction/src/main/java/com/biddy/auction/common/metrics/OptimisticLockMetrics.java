package com.biddy.auction.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 낙관적 락 입찰의 충돌, 재시도, 처리 시간을 수집한다. */
@Component
@Slf4j
public class OptimisticLockMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter optimisticLockConflicts;
    private final Counter retryAttempts;
    private final Counter retrySuccesses;
    private final Counter retryFailures;
    private final Timer bidProcessingTime;
    private final Timer retryRecoveryTime;

    public OptimisticLockMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.optimisticLockConflicts = Counter.builder("bid.optimistic_lock.conflicts")
                .description("Number of optimistic lock conflicts")
                .register(meterRegistry);
        this.retryAttempts = Counter.builder("bid.retry.attempts")
                .description("Number of additional optimistic lock attempts")
                .register(meterRegistry);
        this.retrySuccesses = Counter.builder("bid.retry.successes")
                .description("Number of bids committed after a retry")
                .register(meterRegistry);
        this.retryFailures = Counter.builder("bid.retry.failures")
                .description("Number of bids rejected after retry exhaustion")
                .register(meterRegistry);
        this.bidProcessingTime = Timer.builder("bid.processing.time")
                .description("End-to-end bid processing time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.retryRecoveryTime = Timer.builder("bid.retry.recovery.time")
                .description("Time until a conflicted bid commits successfully")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void recordOptimisticLockConflict(String auctionId) {
        optimisticLockConflicts.increment();
        log.debug("Optimistic lock conflict recorded for auction: {}", auctionId);
    }

    public void recordRetryAttempt(String auctionId, int attemptNumber) {
        retryAttempts.increment();
        log.debug("Retry attempt {} recorded for auction: {}", attemptNumber, auctionId);
    }

    public void recordRetrySuccess(String auctionId, int attemptNumber, Duration duration) {
        retrySuccesses.increment();
        retryRecoveryTime.record(duration);
        log.debug("Retry succeeded on attempt {} for auction: {}", attemptNumber, auctionId);
    }

    public void recordRetryFailure(String auctionId, int maxAttempts) {
        retryFailures.increment();
        log.warn("Retry failed after {} attempts for auction: {}", maxAttempts, auctionId);
    }

    public Timer.Sample startBidProcessing() {
        return Timer.start(meterRegistry);
    }

    public void recordBidProcessing(Timer.Sample sample) {
        sample.stop(bidProcessingTime);
    }
}
