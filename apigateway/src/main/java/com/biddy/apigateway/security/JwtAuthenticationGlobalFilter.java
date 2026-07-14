package com.biddy.apigateway.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";

    private final JwtTokenProvider jwtTokenProvider;
    private final ReactiveStringRedisTemplate redisTemplate;

    // 토큰 없이도 통과시켜야 하는 경로 (인증 자체가 필요 없는 API)
    private static final List<String> WHITELIST = List.of(
            "/api/members/signup",
            "/api/members/login",
            "/api/members/email",
            "/api/members/auth/refresh",
            "/api/chatbot",
            "/v3/api-docs",
            "/swagger-ui",
            "/api/ws-chat"
    );

    // 비로그인도 조회 가능하지만, 로그인 상태면 회원 정보를 같이 넘겨줘야 하는 경로
    // (예: 상품 목록/상세는 비로그인도 보이지만, 로그인 상태면 찜 여부 등을 함께 응답해야 함)
    private static final List<String> OPTIONAL_AUTH_GET_WHITELIST = List.of(
            "/api/products",
            "/api/members/auth/refresh"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod method = exchange.getRequest().getMethod();

        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        String token = resolveToken(exchange.getRequest());
        boolean optionalAuth = HttpMethod.GET.equals(method) && isOptionalAuthPath(path);

        if (token == null || !jwtTokenProvider.validateToken(token)) {
            if (optionalAuth) {
                // 비로그인 조회 허용: 토큰 없이 그대로 통과 (X-Member-Id 헤더 없음)
                return chain.filter(exchange);
            }
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        return redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + token)
                // Redis 장애 시에는 블랙리스트 체크만 건너뛰고 서명/만료 검증 결과로 통과시킴 (fail-open)
                .onErrorResume(e -> {
                    log.warn("JWT 블랙리스트 조회 실패, 검증 스킵: {}", e.getMessage());
                    return Mono.just(false);
                })
                .defaultIfEmpty(false)
                .flatMap(isBlacklisted -> {
                    if (Boolean.TRUE.equals(isBlacklisted)) {
                        if (optionalAuth) {
                            return chain.filter(exchange);
                        }
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }

                    String memberId = jwtTokenProvider.getMemberId(token);
                    String role = jwtTokenProvider.getRole(token);

                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header("X-Member-Id", memberId)
                            .header("X-Member-Role", role)
                            .build();

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                });
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(path::startsWith);
    }

    private boolean isOptionalAuthPath(String path) {
        return OPTIONAL_AUTH_GET_WHITELIST.stream().anyMatch(path::startsWith);
    }

    private String resolveToken(ServerHttpRequest request) {
        String bearer = request.getHeaders().getFirst("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    @Override
    public int getOrder() {
        return -1; // SecurityConfig보다 먼저 실행되도록
    }
}
