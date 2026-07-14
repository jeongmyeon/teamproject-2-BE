package com.biddy.memberservice.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 공통 설정.
 *
 * <p>용도: 로그아웃된 JWT access token 블랙리스트 저장
 * ({@code jwt:blacklist:{token}}, TTL = 토큰 남은 만료시간)</p>
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
