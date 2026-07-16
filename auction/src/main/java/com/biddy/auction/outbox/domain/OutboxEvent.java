package com.biddy.auction.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox 패턴 이벤트 엔티티.
 *
 * <p>Transactional Outbox 패턴을 구현하여 이벤트 발행의 원자성을 보장한다.
 * 비즈니스 트랜잭션과 동일한 트랜잭션에서 이벤트를 DB에 저장하고,
 * 별도의 스케줄러가 주기적으로 Kafka로 발행한다.</p>
 *
 * <p>상태 전이:
 * <ul>
 *   <li>PENDING → PROCESSED (성공)</li>
 *   <li>PENDING → FAILED (재시도 횟수 초과)</li>
 * </ul></p>
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 집합체 타입 (예: Auction, Bid) */
    @Column(nullable = false, length = 50)
    private String aggregateType;

    /** 집합체 ID (예: A-TEST01) */
    @Column(nullable = false, length = 255)
    private String aggregateId;

    /** Kafka 토픽명 */
    @Column(nullable = false, length = 100)
    private String topic;

    /** JSON 직렬화된 이벤트 페이로드 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** 이벤트 상태 (PENDING, PROCESSED, FAILED) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    /** 이벤트 생성 시각 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 이벤트 처리 완료 시각 */
    private LocalDateTime processedAt;

    /** 마지막 발행 시도 시각 */
    private LocalDateTime lastAttemptAt;

    /** 재시도 횟수 */
    @Column(nullable = false)
    private int retryCount;

    /** 마지막 에러 메시지 (최대 1000자) */
    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Builder
    public OutboxEvent(String aggregateType, String aggregateId, String topic, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.retryCount = 0;
    }

    /**
     * 이벤트 발행 성공으로 마킹한다.
     * 상태를 PROCESSED로 변경하고 처리 시각을 기록한다.
     */
    public void markAsProcessed() {
        this.status = OutboxStatus.PROCESSED;
        this.processedAt = LocalDateTime.now();
        this.lastAttemptAt = this.processedAt;
        this.lastError = null;
    }

    /**
     * 이벤트 발행 실패로 마킹한다.
     * 재시도 횟수를 증가시키고, 최대 재시도 횟수 초과 시 FAILED로 변경한다.
     *
     * @param throwable 발생한 예외
     * @param maxRetryCount 최대 재시도 횟수
     */
    public void markAsFailed(Throwable throwable, int maxRetryCount) {
        this.retryCount++;
        this.lastAttemptAt = LocalDateTime.now();
        this.lastError = resolveErrorMessage(throwable);
        if (this.retryCount >= maxRetryCount) {
            this.status = OutboxStatus.FAILED;
        }
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 예외로부터 에러 메시지를 추출한다 (최대 1000자).
     */
    private String resolveErrorMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}