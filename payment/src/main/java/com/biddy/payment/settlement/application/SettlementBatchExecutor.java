package com.biddy.payment.settlement.application;

import com.biddy.payment.settlement.domain.Settlement;
import com.biddy.payment.settlement.domain.SettlementStatus;
import com.biddy.payment.settlement.infrastructure.persistence.SettlementRepository;
import com.biddy.payment.wallet.application.DepositService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementBatchExecutor {

    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int MAX_BATCH_SIZE = 2_000;

    private final SettlementRepository settlementRepository;
    private final DepositService depositService;

    public SettlementBatchExecutor(
            SettlementRepository settlementRepository,
            DepositService depositService
    ) {
        this.settlementRepository = settlementRepository;
        this.depositService = depositService;
    }

    @Transactional
    public int completePendingSettlementChunk(int batchSize) {
        int querySize = normalizeBatchSize(batchSize);
        List<Settlement> pendingSettlements = settlementRepository.findByStatusOrderByCreatedAtAsc(
                SettlementStatus.PENDING,
                PageRequest.of(0, querySize)
        );

        Map<Long, List<Settlement>> settlementsBySeller = pendingSettlements.stream()
                .filter(settlement -> settlement.getStatus() == SettlementStatus.PENDING)
                .collect(Collectors.groupingBy(Settlement::getUserId));

        settlementsBySeller.forEach((sellerId, settlements) -> {
            long totalSettlementAmount = settlements.stream()
                    .mapToLong(Settlement::getSettlementAmount)
                    .sum();
            String batchReferenceId = batchReferenceId(sellerId, settlements);

            settlements.forEach(Settlement::complete);
            depositService.increaseForSettlement(sellerId, totalSettlementAmount, batchReferenceId);
        });

        return pendingSettlements.size();
    }

    private int normalizeBatchSize(int batchSize) {
        if (batchSize <= 0) {
            return DEFAULT_BATCH_SIZE;
        }
        return Math.min(batchSize, MAX_BATCH_SIZE);
    }

    private String batchReferenceId(Long sellerId, List<Settlement> settlements) {
        Long firstId = settlements.getFirst().getId();
        Long lastId = settlements.getLast().getId();
        return "batch-" + sellerId + "-" + firstId + "-" + lastId;
    }
}
