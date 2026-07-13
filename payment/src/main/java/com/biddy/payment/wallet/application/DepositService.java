package com.biddy.payment.wallet.application;

import com.biddy.payment.wallet.domain.DepositAccount;
import com.biddy.payment.wallet.domain.DepositTransaction;
import com.biddy.payment.wallet.domain.DepositTransactionType;
import com.biddy.payment.wallet.infrastructure.client.toss.TossPaymentClient;
import com.biddy.payment.wallet.infrastructure.client.toss.TossPaymentConfirmResponse;
import com.biddy.payment.wallet.presentation.request.DepositAdjustRequest;
import com.biddy.payment.wallet.presentation.response.DepositBalanceResponse;
import com.biddy.payment.wallet.presentation.request.DepositChargeCancelRequest;
import com.biddy.payment.wallet.presentation.request.DepositChargeRequest;
import com.biddy.payment.wallet.presentation.response.DepositTransactionResponse;
import com.biddy.payment.wallet.presentation.request.DepositWithdrawRequest;
import com.biddy.payment.wallet.infrastructure.persistence.DepositAccountRepository;
import com.biddy.payment.wallet.infrastructure.persistence.DepositTransactionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class DepositService {

    private static final String TOSS_PAYMENT_REFERENCE_TYPE = "TOSS_PAYMENT";
    private static final String TOSS_PAYMENT_CANCEL_REFERENCE_TYPE = "TOSS_PAYMENT_CANCEL";
    private static final int DEFAULT_TRANSACTION_SIZE = 30;
    private static final int MAX_TRANSACTION_SIZE = 100;

    private final DepositAccountRepository accountRepository;
    private final DepositTransactionRepository transactionRepository;
    private final TossPaymentClient tossPaymentClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long transactionCacheTtlSeconds;

    public DepositService(
            DepositAccountRepository accountRepository,
            DepositTransactionRepository transactionRepository,
            TossPaymentClient tossPaymentClient,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${wallet.transactions.cache-ttl-seconds:30}") long transactionCacheTtlSeconds
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.tossPaymentClient = tossPaymentClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.transactionCacheTtlSeconds = transactionCacheTtlSeconds;
    }

    @Transactional(readOnly = true)
    public DepositBalanceResponse getBalance(Long userId) {
        DepositAccount account = accountRepository.findByUserId(userId)
                .orElseGet(() -> DepositAccount.open(userId));
        return DepositBalanceResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<DepositTransactionResponse> getTransactions(Long userId) {
        return getTransactions(userId, DEFAULT_TRANSACTION_SIZE);
    }

    @Transactional(readOnly = true)
    public List<DepositTransactionResponse> getTransactions(Long userId, int size) {
        int querySize = normalizeTransactionSize(size);
        String cacheKey = transactionCacheKey(userId, querySize);

        List<DepositTransactionResponse> cachedTransactions = getCachedTransactions(cacheKey);
        if (cachedTransactions != null) {
            return cachedTransactions;
        }

        List<DepositTransactionResponse> transactions = transactionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, querySize))
                .stream()
                .map(DepositTransactionResponse::from)
                .toList();
        cacheTransactions(cacheKey, transactions);
        return transactions;
    }

    @Transactional(readOnly = true)
    public List<DepositTransactionResponse> getAllTransactions(Long userId) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(DepositTransactionResponse::from)
                .toList();
    }

    @Transactional
    public void openAccount(Long userId) {
        accountRepository.findByUserId(userId)
                .orElseGet(() -> accountRepository.save(DepositAccount.open(userId)));
    }

    @Transactional
    public DepositBalanceResponse charge(DepositChargeRequest request) {
        DepositBalanceResponse duplicated = findDuplicatedCharge(request);
        if (duplicated != null) {
            return duplicated;
        }

        TossPaymentConfirmResponse confirmResponse = tossPaymentClient.confirm(
                request.paymentKey(),
                request.orderId(),
                request.amount()
        );
        validateTossConfirmResponse(request, confirmResponse);

        DepositAccount account = getOrCreateWithLock(request.userId());

        duplicated = findDuplicatedCharge(request);
        if (duplicated != null) {
            return duplicated;
        }

        account.increase(request.amount());
        record(account, DepositTransactionType.CHARGE, request.amount(), TOSS_PAYMENT_REFERENCE_TYPE, request.paymentKey(), "Toss Payments 예치금 충전");
        return DepositBalanceResponse.from(account);
    }

    @Transactional
    public DepositBalanceResponse cancelCharge(DepositChargeCancelRequest request) {
        DepositBalanceResponse duplicated = findDuplicatedChargeCancel(request);
        if (duplicated != null) {
            return duplicated;
        }

        DepositTransaction charge = transactionRepository.findByReferenceTypeAndReferenceId(
                        TOSS_PAYMENT_REFERENCE_TYPE,
                        request.paymentKey()
                )
                .orElseThrow(() -> new IllegalStateException("취소할 예치금 충전 내역을 찾을 수 없습니다."));
        validateChargeCancelRequest(request, charge);

        DepositAccount account = getOrCreateWithLock(request.userId());
        account.decrease(request.amount());
        tossPaymentClient.cancel(request.paymentKey(), request.reason(), request.amount());

        record(
                account,
                DepositTransactionType.CANCEL,
                -request.amount(),
                TOSS_PAYMENT_CANCEL_REFERENCE_TYPE,
                request.paymentKey(),
                request.reason()
        );
        return DepositBalanceResponse.from(account);
    }

    @Transactional
    public DepositBalanceResponse withdraw(DepositWithdrawRequest request) {
        DepositAccount account = getOrCreateWithLock(request.userId());
        account.decrease(request.amount());
        record(account, DepositTransactionType.WITHDRAW, -request.amount(), "DEPOSIT_WITHDRAW", null, "예치금 출금 신청");
        return DepositBalanceResponse.from(account);
    }

    @Transactional
    public DepositBalanceResponse adjust(DepositAdjustRequest request) {
        if (request.amount() == 0) {
            throw new IllegalArgumentException("조정 금액은 0일 수 없습니다.");
        }
        DepositAccount account = getOrCreateWithLock(request.userId());
        account.adjust(request.amount());
        record(account, DepositTransactionType.ADJUSTMENT, request.amount(), "DEPOSIT_ADJUST", null, request.reason());
        return DepositBalanceResponse.from(account);
    }

    @Transactional
    public void decreaseForPayment(Long userId, Long amount, String referenceId) {
        DepositAccount account = getOrCreateWithLock(userId);
        account.decrease(amount);
        record(account, DepositTransactionType.PAYMENT, -amount, "PAYMENT", referenceId, "예치금 결제 차감");
    }

    @Transactional
    public void increaseForCancel(Long userId, Long amount, String referenceId, DepositTransactionType type, String reason) {
        DepositAccount account = getOrCreateWithLock(userId);
        account.increase(amount);
        record(account, type, amount, "PAYMENT", referenceId, reason);
    }

    @Transactional
    public void increaseForSettlement(Long userId, Long amount, String referenceId) {
        DepositAccount account = getOrCreateWithLock(userId);
        account.increase(amount);
        record(account, DepositTransactionType.SETTLEMENT, amount, "SETTLEMENT", referenceId, "판매자 정산 지급");
    }

    private DepositAccount getOrCreateWithLock(Long userId) {
        return accountRepository.findWithLockByUserId(userId)
                .orElseGet(() -> accountRepository.save(DepositAccount.open(userId)));
    }

    private DepositBalanceResponse findDuplicatedCharge(DepositChargeRequest request) {
        return transactionRepository.findByReferenceTypeAndReferenceId(TOSS_PAYMENT_REFERENCE_TYPE, request.paymentKey())
                .map(transaction -> {
                    if (!Objects.equals(transaction.getUserId(), request.userId())) {
                        throw new IllegalStateException("이미 다른 사용자에게 처리된 paymentKey입니다.");
                    }
                    return getBalance(request.userId());
                })
                .orElse(null);
    }

    private DepositBalanceResponse findDuplicatedChargeCancel(DepositChargeCancelRequest request) {
        return transactionRepository.findByReferenceTypeAndReferenceId(TOSS_PAYMENT_CANCEL_REFERENCE_TYPE, request.paymentKey())
                .map(transaction -> {
                    if (!Objects.equals(transaction.getUserId(), request.userId())) {
                        throw new IllegalStateException("이미 다른 사용자에게 취소 처리된 paymentKey입니다.");
                    }
                    return getBalance(request.userId());
                })
                .orElse(null);
    }

    private void validateChargeCancelRequest(DepositChargeCancelRequest request, DepositTransaction charge) {
        if (charge.getType() != DepositTransactionType.CHARGE) {
            throw new IllegalStateException("예치금 충전 내역만 취소할 수 있습니다.");
        }
        if (!Objects.equals(charge.getUserId(), request.userId())) {
            throw new IllegalStateException("충전 사용자와 취소 요청 사용자가 일치하지 않습니다.");
        }
        if (!Objects.equals(charge.getAmount(), request.amount())) {
            throw new IllegalStateException("충전 금액과 취소 금액이 일치하지 않습니다.");
        }
    }

    private void validateTossConfirmResponse(
            DepositChargeRequest request,
            TossPaymentConfirmResponse response
    ) {
        if (response == null || !response.isDone()) {
            throw new IllegalStateException("Toss Payments 결제 승인에 실패했습니다.");
        }
        if (!Objects.equals(response.paymentKey(), request.paymentKey())) {
            throw new IllegalStateException("Toss Payments paymentKey가 요청과 일치하지 않습니다.");
        }
        if (!Objects.equals(response.orderId(), request.orderId())) {
            throw new IllegalStateException("Toss Payments orderId가 요청과 일치하지 않습니다.");
        }
        if (!Objects.equals(response.totalAmount(), request.amount())) {
            throw new IllegalStateException("Toss Payments 승인 금액이 요청 금액과 일치하지 않습니다.");
        }
    }

    private void record(
            DepositAccount account,
            DepositTransactionType type,
            Long amount,
            String referenceType,
            String referenceId,
            String reason
    ) {
        transactionRepository.save(DepositTransaction.record(
                account.getUserId(),
                type,
                amount,
                account.getBalance(),
                referenceType,
                referenceId,
                reason
        ));
        evictTransactionCache(account.getUserId());
    }

    private int normalizeTransactionSize(int size) {
        if (size <= 0) {
            return DEFAULT_TRANSACTION_SIZE;
        }
        return Math.min(size, MAX_TRANSACTION_SIZE);
    }

    private List<DepositTransactionResponse> getCachedTransactions(String cacheKey) {
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached == null) {
                return null;
            }
            JavaType type = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, DepositTransactionResponse.class);
            return objectMapper.readValue(cached, type);
        } catch (DataAccessException exception) {
            log.warn("Wallet transaction cache read failed. cacheKey={}", cacheKey, exception);
            return null;
        } catch (JsonProcessingException exception) {
            log.warn("Wallet transaction cache payload is invalid. cacheKey={}", cacheKey, exception);
            try {
                redisTemplate.delete(cacheKey);
            } catch (DataAccessException dataAccessException) {
                log.warn("Wallet transaction cache delete failed. cacheKey={}", cacheKey, dataAccessException);
            }
            return null;
        }
    }

    private void cacheTransactions(String cacheKey, List<DepositTransactionResponse> transactions) {
        try {
            String payload = objectMapper.writeValueAsString(transactions);
            redisTemplate.opsForValue().set(cacheKey, payload, transactionCacheTtlSeconds, TimeUnit.SECONDS);
        } catch (DataAccessException exception) {
            log.warn("Wallet transaction cache write failed. cacheKey={}", cacheKey, exception);
        } catch (JsonProcessingException exception) {
            log.warn("Wallet transaction cache serialization failed. cacheKey={}", cacheKey, exception);
        }
    }

    private void evictTransactionCache(Long userId) {
        try {
            Set<String> keys = redisTemplate.keys(transactionCachePattern(userId) + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (DataAccessException exception) {
            log.warn("Wallet transaction cache eviction failed. userId={}", userId, exception);
        }
    }

    private String transactionCacheKey(Long userId, int size) {
        return transactionCachePattern(userId) + ":" + size;
    }

    private String transactionCachePattern(Long userId) {
        return "wallet:transactions:" + userId + ":recent";
    }
}
