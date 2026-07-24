package com.biddy.payment.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.biddy.payment.payment.domain.PaymentMethod;
import com.biddy.payment.payment.domain.event.PaymentCompletedEvent;
import com.biddy.payment.settlement.domain.SettlementStatus;
import com.biddy.payment.settlement.infrastructure.persistence.SettlementRepository;
import com.biddy.payment.wallet.application.DepositService;
import com.biddy.payment.wallet.presentation.response.DepositBalanceResponse;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SettlementServiceTest {

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private DepositService depositService;

    @Test
    void completeByOrderId_completesPendingSettlementAndIncreasesSellerDeposit() {
        Long orderId = 300L;
        Long sellerId = 30L;
        Long amount = 100_000L;

        settlementService.createPendingSettlement(new PaymentCompletedEvent(
                UUID.randomUUID(),
                1L,
                orderId,
                10L,
                sellerId,
                amount,
                PaymentMethod.WALLET,
                LocalDateTime.now()
        ));

        settlementService.completeByOrderId(orderId);

        var settlement = settlementRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(settlement.getCommissionAmount()).isEqualTo(5_000L);
        assertThat(settlement.getSettlementAmount()).isEqualTo(95_000L);

        DepositBalanceResponse balance = depositService.getBalance(sellerId);
        assertThat(balance.balance()).isEqualTo(95_000L);
    }

    @Test
    void completeByOrderId_ignoresAlreadyCompletedSettlement() {
        Long orderId = 301L;
        Long sellerId = 31L;
        Long amount = 80_000L;

        settlementService.createPendingSettlement(new PaymentCompletedEvent(
                UUID.randomUUID(),
                2L,
                orderId,
                11L,
                sellerId,
                amount,
                PaymentMethod.NORMAL,
                LocalDateTime.now()
        ));

        settlementService.completeByOrderId(orderId);
        settlementService.completeByOrderId(orderId);

        DepositBalanceResponse balance = depositService.getBalance(sellerId);
        assertThat(balance.balance()).isEqualTo(76_000L);
    }

    @Test
    void completePendingSettlements_completesPendingSettlementsInBatch() {
        Long sellerId = 32L;

        settlementService.createPendingSettlement(new PaymentCompletedEvent(
                UUID.randomUUID(),
                3L,
                302L,
                12L,
                sellerId,
                100_000L,
                PaymentMethod.WALLET,
                LocalDateTime.now()
        ));
        settlementService.createPendingSettlement(new PaymentCompletedEvent(
                UUID.randomUUID(),
                4L,
                303L,
                13L,
                sellerId,
                40_000L,
                PaymentMethod.NORMAL,
                LocalDateTime.now()
        ));

        int processedCount = settlementService.completePendingSettlements(1);

        assertThat(processedCount).isGreaterThanOrEqualTo(2);
        assertThat(settlementRepository.findByOrderId(302L).orElseThrow().getStatus()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(settlementRepository.findByOrderId(303L).orElseThrow().getStatus()).isEqualTo(SettlementStatus.COMPLETED);

        DepositBalanceResponse balance = depositService.getBalance(sellerId);
        assertThat(balance.balance()).isEqualTo(133_000L);
    }
}
