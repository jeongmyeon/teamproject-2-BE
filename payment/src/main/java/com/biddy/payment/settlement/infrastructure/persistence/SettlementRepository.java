package com.biddy.payment.settlement.infrastructure.persistence;

import com.biddy.payment.settlement.domain.Settlement;
import com.biddy.payment.settlement.domain.SettlementStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    List<Settlement> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Settlement> findAllByOrderByCreatedAtDesc();

    boolean existsByOrderId(Long orderId);

    Optional<Settlement> findByOrderId(Long orderId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<Settlement> findByStatusOrderByCreatedAtAsc(SettlementStatus status, Pageable pageable);
}
