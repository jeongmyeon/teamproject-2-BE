package com.biddy.auction.outbox.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;

/**
 * Outbox 이벤트 리포지토리.
 *
 * <p>Pessimistic locking을 사용하여 동시성 문제를 방지한다.</p>
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 지정된 상태의 이벤트를 최대 100개까지 조회한다.
     * 생성 시각 기준 오름차순으로 정렬하여 가장 오래된 이벤트부터 처리한다.
     *
     * @param status 조회할 이벤트 상태
     * @return 조회된 이벤트 목록 (최대 100개)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
