package com.biddy.auction.common.config;

import jakarta.persistence.OptimisticLockException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.HashMap;
import java.util.Map;

/** 낙관적 락 충돌에만 적용하는 제한 재시도 설정. */
@Configuration
public class OptimisticLockRetryConfig {

    private static final int MAX_ATTEMPTS = 3;

    @Bean(name = "optimisticLockRetryTemplate")
    public RetryTemplate optimisticLockRetryTemplate() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(OptimisticLockingFailureException.class, true);
        retryableExceptions.put(OptimisticLockException.class, true);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(MAX_ATTEMPTS, retryableExceptions);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(10);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(200);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        retryTemplate.setThrowLastExceptionOnExhausted(true);
        return retryTemplate;
    }
}
