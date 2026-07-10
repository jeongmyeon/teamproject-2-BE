package com.biddy.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Redis 공통 설정 (Gateway용).
 *
 * <p>Gateway는 WebFlux 기반이라 블로킹 RedisTemplate 대신
 * ReactiveStringRedisTemplate을 사용한다.</p>
 *
 * <p>용도: 로그아웃된 JWT 블랙리스트 조회 ({@code jwt:blacklist:{token}})</p>
 */
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveStringRedisTemplate(connectionFactory);
    }
}
