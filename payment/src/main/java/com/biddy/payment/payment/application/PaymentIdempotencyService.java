package com.biddy.payment.payment.application;

import com.biddy.payment.global.exception.PaymentConflictException;
import com.biddy.payment.global.exception.PaymentRedisUnavailableException;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class PaymentIdempotencyService {

    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED_PREFIX = "COMPLETED:";
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final Duration idempotencyTtl;
    private final Duration orderLockTtl;

    public PaymentIdempotencyService(
            StringRedisTemplate redisTemplate,
            @Value("${payment.idempotency.ttl-minutes:30}") long idempotencyTtlMinutes,
            @Value("${payment.idempotency.order-lock-ttl-minutes:5}") long orderLockTtlMinutes
    ) {
        this.redisTemplate = redisTemplate;
        this.idempotencyTtl = Duration.ofMinutes(idempotencyTtlMinutes);
        this.orderLockTtl = Duration.ofMinutes(orderLockTtlMinutes);
    }

    public Optional<Long> begin(Long memberId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);

        String key = idempotencyKey(memberId, idempotencyKey);
        try {
            String currentValue = redisTemplate.opsForValue().get(key);
            Optional<Long> completedPaymentId = resolveCompletedPaymentId(currentValue);
            if (completedPaymentId.isPresent()) {
                return completedPaymentId;
            }
            if (PROCESSING.equals(currentValue)) {
                throw new PaymentConflictException("이미 처리 중인 결제 요청입니다.");
            }

            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, PROCESSING, idempotencyTtl);
            if (Boolean.TRUE.equals(acquired)) {
                return Optional.empty();
            }

            return handleAlreadyExistingKey(key);
        } catch (PaymentConflictException exception) {
            throw exception;
        } catch (RedisConnectionFailureException exception) {
            throw new PaymentRedisUnavailableException("결제 중복 방지 저장소에 연결할 수 없어 결제를 진행할 수 없습니다.", exception);
        } catch (DataAccessException exception) {
            throw new PaymentRedisUnavailableException("결제 중복 방지 저장소 처리 중 오류가 발생했습니다.", exception);
        }
    }

    public String acquireOrderLock(Long orderId) {
        String key = orderLockKey(orderId);
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, orderLockTtl);
            if (!Boolean.TRUE.equals(acquired)) {
                throw new PaymentConflictException("이미 처리 중인 주문 결제입니다.");
            }
            return token;
        } catch (PaymentConflictException exception) {
            throw exception;
        } catch (RedisConnectionFailureException exception) {
            throw new PaymentRedisUnavailableException("결제 중복 방지 저장소에 연결할 수 없어 결제를 진행할 수 없습니다.", exception);
        } catch (DataAccessException exception) {
            throw new PaymentRedisUnavailableException("주문 결제 락 처리 중 오류가 발생했습니다.", exception);
        }
    }

    public void complete(Long memberId, String idempotencyKey, Long paymentId) {
        try {
            redisTemplate.opsForValue().set(idempotencyKey(memberId, idempotencyKey), COMPLETED_PREFIX + paymentId, idempotencyTtl);
        } catch (DataAccessException exception) {
            throw new PaymentRedisUnavailableException("결제 멱등성 결과 저장 중 오류가 발생했습니다.", exception);
        }
    }

    public void clear(Long memberId, String idempotencyKey) {
        try {
            redisTemplate.delete(idempotencyKey(memberId, idempotencyKey));
        } catch (DataAccessException exception) {
            throw new PaymentRedisUnavailableException("결제 멱등성 키 삭제 중 오류가 발생했습니다.", exception);
        }
    }

    public void releaseOrderLock(Long orderId, String token) {
        try {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(orderLockKey(orderId)), token);
        } catch (DataAccessException exception) {
            throw new PaymentRedisUnavailableException("주문 결제 락 해제 중 오류가 발생했습니다.", exception);
        }
    }

    private Optional<Long> handleAlreadyExistingKey(String key) {
        String currentValue = redisTemplate.opsForValue().get(key);
        Optional<Long> completedPaymentId = resolveCompletedPaymentId(currentValue);
        if (completedPaymentId.isPresent()) {
            return completedPaymentId;
        }
        throw new PaymentConflictException("이미 처리 중인 결제 요청입니다.");
    }

    private Optional<Long> resolveCompletedPaymentId(String value) {
        if (value != null && value.startsWith(COMPLETED_PREFIX)) {
            return Optional.of(Long.parseLong(value.substring(COMPLETED_PREFIX.length())));
        }
        return Optional.empty();
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key 헤더는 필수입니다.");
        }
    }

    private String idempotencyKey(Long memberId, String idempotencyKey) {
        return "idempotency:payment:" + memberId + ":" + idempotencyKey;
    }

    private String orderLockKey(Long orderId) {
        return "payment:order-lock:" + orderId;
    }
}
