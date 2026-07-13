package com.biddy.payment.wallet.infrastructure.persistence;

import com.biddy.payment.wallet.domain.DepositTransaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositTransactionRepository extends JpaRepository<DepositTransaction, Long> {

    List<DepositTransaction> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<DepositTransaction> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<DepositTransaction> findByReferenceTypeAndReferenceId(String referenceType, String referenceId);
}
