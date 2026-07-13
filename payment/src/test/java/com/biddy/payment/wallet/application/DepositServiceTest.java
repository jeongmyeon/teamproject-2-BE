package com.biddy.payment.wallet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.biddy.payment.wallet.domain.DepositAccount;
import com.biddy.payment.wallet.domain.DepositTransaction;
import com.biddy.payment.wallet.domain.DepositTransactionType;
import com.biddy.payment.wallet.infrastructure.client.toss.TossPaymentClient;
import com.biddy.payment.wallet.infrastructure.persistence.DepositAccountRepository;
import com.biddy.payment.wallet.infrastructure.persistence.DepositTransactionRepository;
import com.biddy.payment.wallet.presentation.request.DepositChargeCancelRequest;
import com.biddy.payment.wallet.presentation.response.DepositBalanceResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

class DepositServiceTest {

    private final DepositAccountRepository accountRepository = Mockito.mock(DepositAccountRepository.class);
    private final DepositTransactionRepository transactionRepository = Mockito.mock(DepositTransactionRepository.class);
    private final TossPaymentClient tossPaymentClient = Mockito.mock(TossPaymentClient.class);
    private final StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
    private final DepositService depositService = new DepositService(
            accountRepository,
            transactionRepository,
            tossPaymentClient,
            redisTemplate,
            new ObjectMapper(),
            30L
    );

    @Test
    void openAccount_createsDepositAccountWhenAbsent() {
        Long userId = 1L;
        when(accountRepository.findByUserId(userId)).thenReturn(Optional.empty());

        depositService.openAccount(userId);

        verify(accountRepository).save(any(DepositAccount.class));
    }

    @Test
    void openAccount_ignoresWhenDepositAccountAlreadyExists() {
        Long userId = 1L;
        when(accountRepository.findByUserId(userId)).thenReturn(Optional.of(DepositAccount.open(userId)));

        depositService.openAccount(userId);

        verify(accountRepository, never()).save(any(DepositAccount.class));
    }

    @Test
    void cancelCharge_cancelsTossPaymentAndDecreasesDeposit() {
        Long userId = 1L;
        Long amount = 50_000L;
        String paymentKey = "payment-key-1";
        DepositAccount account = DepositAccount.open(userId);
        account.increase(amount);
        DepositTransaction charge = DepositTransaction.record(
                userId,
                DepositTransactionType.CHARGE,
                amount,
                amount,
                "TOSS_PAYMENT",
                paymentKey,
                "Toss Payments 예치금 충전"
        );

        when(transactionRepository.findByReferenceTypeAndReferenceId("TOSS_PAYMENT_CANCEL", paymentKey))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByReferenceTypeAndReferenceId("TOSS_PAYMENT", paymentKey))
                .thenReturn(Optional.of(charge));
        when(accountRepository.findWithLockByUserId(userId)).thenReturn(Optional.of(account));

        DepositBalanceResponse response = depositService.cancelCharge(new DepositChargeCancelRequest(
                userId,
                amount,
                paymentKey,
                "충전 취소"
        ));

        assertThat(response.balance()).isZero();
        verify(tossPaymentClient).cancel(paymentKey, "충전 취소", amount);

        ArgumentCaptor<DepositTransaction> transactionCaptor = ArgumentCaptor.forClass(DepositTransaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        DepositTransaction cancel = transactionCaptor.getValue();

        assertThat(cancel.getType()).isEqualTo(DepositTransactionType.CANCEL);
        assertThat(cancel.getAmount()).isEqualTo(-amount);
        assertThat(cancel.getReferenceType()).isEqualTo("TOSS_PAYMENT_CANCEL");
        assertThat(cancel.getReferenceId()).isEqualTo(paymentKey);
    }
}
