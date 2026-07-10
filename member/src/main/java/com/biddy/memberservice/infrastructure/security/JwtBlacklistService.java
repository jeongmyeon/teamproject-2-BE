package com.biddy.memberservice.infrastructure.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 로그아웃(또는 강제 무효화)된 JWT access token을 Redis 블랙리스트에 등록한다.
 *
 * <p>Key: {@code jwt:blacklist:{token}}, TTL: 그 토큰의 남은 만료시간.
 * 토큰이 자연 만료되는 시점에 블랙리스트 키도 함께 사라지므로
 * 별도의 정리(cleanup) 로직이 필요 없다.</p>
 *
 * <p>Gateway({@code JwtAuthenticationGlobalFilter})는 매 요청마다
 * 이 키의 존재 여부만 확인해서 즉시 차단 여부를 판단한다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JwtBlacklistService {

    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    public void blacklist(String accessToken) {
        long remainingMillis = jwtTokenProvider.getRemainingMillis(accessToken);
        if (remainingMillis <= 0) {
            // 이미 만료된 토큰은 블랙리스트에 넣을 필요가 없음 (Gateway가 만료 검증에서 이미 걸러냄)
            return;
        }

        redisTemplate.opsForValue().set(
                BLACKLIST_KEY_PREFIX + accessToken,
                "revoked",
                Duration.ofMillis(remainingMillis)
        );
        log.info("access token 블랙리스트 등록 완료 (남은 만료시간={}ms)", remainingMillis);
    }
}
