package com.biddy.auction.outbox.domain;

/**
 * Outbox 이벤트 상태.
 *
 * <p>상태 전이:
 * <ul>
 *   <li>PENDING → PROCESSED (발행 성공)</li>
 *   <li>PENDING → FAILED (재시도 횟수 초과)</li>
 * </ul></p>
 */
public enum OutboxStatus {
    /** 발행 대기 중 */
    PENDING,

    /** 발행 완료 */
    PROCESSED,

    /** 발행 실패 (재시도 횟수 초과) */
    FAILED
}