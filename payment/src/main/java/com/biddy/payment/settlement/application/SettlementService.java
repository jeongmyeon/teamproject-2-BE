package com.biddy.payment.settlement.application;

import com.biddy.payment.wallet.application.DepositService;
import com.biddy.payment.payment.domain.event.PaymentCompletedEvent;
import com.biddy.payment.settlement.domain.Settlement;
import com.biddy.payment.settlement.domain.SettlementItem;
import com.biddy.payment.settlement.domain.SettlementStatus;
import com.biddy.payment.settlement.presentation.request.MonthlySettlementItemRequest;
import com.biddy.payment.settlement.presentation.request.MonthlySettlementRequest;
import com.biddy.payment.settlement.presentation.response.SettlementItemResponse;
import com.biddy.payment.settlement.presentation.response.SettlementResponse;
import com.biddy.payment.settlement.infrastructure.persistence.SettlementItemRepository;
import com.biddy.payment.settlement.infrastructure.persistence.SettlementRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {

    private static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.0500");

    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final DepositService depositService;
    private final SettlementBatchExecutor settlementBatchExecutor;

    public SettlementService(
            SettlementRepository settlementRepository,
            SettlementItemRepository settlementItemRepository,
            DepositService depositService,
            SettlementBatchExecutor settlementBatchExecutor
    ) {
        this.settlementRepository = settlementRepository;
        this.settlementItemRepository = settlementItemRepository;
        this.depositService = depositService;
        this.settlementBatchExecutor = settlementBatchExecutor;
    }

    @Transactional
    public List<SettlementResponse> runMonthlySettlement(MonthlySettlementRequest request) {
        validateYearMonth(request.settlementYearMonth());

        Map<Long, List<MonthlySettlementItemRequest>> itemsBySeller = request.items().stream()
                .collect(Collectors.groupingBy(MonthlySettlementItemRequest::sellerId));

        return itemsBySeller.entrySet().stream()
                .map(entry -> createSellerSettlement(entry.getKey(), request.settlementYearMonth(), request.commissionRate(), entry.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SettlementResponse> getSettlements(Long userId) {
        List<Settlement> settlements = settlementRepository.findByUserIdOrderByCreatedAtDesc(userId);

        return settlements.stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SettlementResponse getSettlement(Long id, Long memberId) {
        Settlement settlement = settlementRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("정산을 찾을 수 없습니다. id=" + id));
        validateSettlementOwner(settlement, memberId);
        return toResponse(settlement);
    }

    @Transactional
    public void createPendingSettlement(PaymentCompletedEvent event) {
        if (settlementRepository.existsByOrderId(event.orderId())) {
            return;
        }

        long commissionAmount = calculateCommission(event.amount(), DEFAULT_COMMISSION_RATE);
        Settlement settlement = Settlement.createPending(
                event.sellerId(),
                event.orderId(),
                event.amount(),
                DEFAULT_COMMISSION_RATE,
                commissionAmount,
                event.amount() - commissionAmount
        );
        settlement = settlementRepository.save(settlement);

        settlementItemRepository.save(SettlementItem.create(
                settlement.getId(),
                event.orderId(),
                event.amount(),
                commissionAmount,
                event.amount() - commissionAmount
        ));
    }

    @Transactional
    public void cancelPendingSettlement(Long orderId) {
        settlementRepository.findByOrderId(orderId)
                .ifPresent(Settlement::cancel);
    }

    @Transactional
    public void completeByOrderId(Long orderId) {
        Settlement settlement = settlementRepository.findByOrderId(orderId)
                .orElseThrow(() -> new EntityNotFoundException("주문 정산을 찾을 수 없습니다. orderId=" + orderId));

        if (settlement.getStatus() == SettlementStatus.COMPLETED) {
            return;
        }

        settlement.complete();
        depositService.increaseForSettlement(
                settlement.getUserId(),
                settlement.getSettlementAmount(),
                String.valueOf(settlement.getId())
        );
    }

    public int completePendingSettlements(int batchSize) {
        int processedCount = 0;

        while (true) {
            int chunkCount = settlementBatchExecutor.completePendingSettlementChunk(batchSize);
            if (chunkCount == 0) {
                return processedCount;
            }
            processedCount += chunkCount;
        }
    }

    private SettlementResponse createSellerSettlement(
            Long sellerId,
            String settlementYearMonth,
            BigDecimal commissionRate,
            List<MonthlySettlementItemRequest> itemRequests
    ) {
        long totalAmount = itemRequests.stream()
                .mapToLong(MonthlySettlementItemRequest::saleAmount)
                .sum();
        long commissionAmount = calculateCommission(totalAmount, commissionRate);
        long settlementAmount = totalAmount - commissionAmount;

        Settlement settlement = Settlement.create(
                sellerId,
                settlementYearMonth,
                totalAmount,
                commissionRate,
                commissionAmount,
                settlementAmount
        );
        settlement = settlementRepository.save(settlement);

        for (MonthlySettlementItemRequest itemRequest : itemRequests) {
            long itemCommission = calculateCommission(itemRequest.saleAmount(), commissionRate);
            SettlementItem item = SettlementItem.create(
                    settlement.getId(),
                    itemRequest.orderId(),
                    itemRequest.saleAmount(),
                    itemCommission,
                    itemRequest.saleAmount() - itemCommission
            );
            settlementItemRepository.save(item);
        }

        settlement.complete();
        depositService.increaseForSettlement(sellerId, settlementAmount, String.valueOf(settlement.getId()));
        return toResponse(settlement);
    }

    private long calculateCommission(long amount, BigDecimal commissionRate) {
        return BigDecimal.valueOf(amount)
                .multiply(commissionRate)
                .setScale(0, RoundingMode.FLOOR)
                .longValue();
    }

    private void validateYearMonth(String yearMonth) {
        if (!yearMonth.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("정산 연월은 yyyy-MM 형식이어야 합니다.");
        }
    }

    private void validateSettlementOwner(Settlement settlement, Long memberId) {
        if (!Objects.equals(settlement.getUserId(), memberId)) {
            throw new IllegalStateException("본인의 정산만 조회할 수 있습니다.");
        }
    }

    private SettlementResponse toResponse(Settlement settlement) {
        List<SettlementItemResponse> items = settlementItemRepository.findBySettlementId(settlement.getId()).stream()
                .map(SettlementItemResponse::from)
                .toList();
        return SettlementResponse.from(settlement, items);
    }
}
