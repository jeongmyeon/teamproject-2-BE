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
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";

    private final JwtTokenProvider jwtTokenProvider;
    private final ReactiveStringRedisTemplate redisTemplate;

    // 토큰 없이도 통과시켜야 하는 경로 (인증 자체가 필요 없는 API)
    // 주의: /api/v1/auctions 는 여기 넣으면 안 됨 - POST bids/watch 같은 인증 필요 경로까지
    // path::startsWith 로 전부 걸려서 토큰 검증이 통째로 스킵됨 (아래 OPTIONAL_AUTH_GET_WHITELIST 참고)
    private static final List<String> WHITELIST = List.of(
            "/api/members/signup",
            "/api/members/login",
            "/api/members/reissue",
            "/api/members/email", // 이메일 인증 발송 및 검증 허용
            "/api/chatbot",
            "/v3/api-docs",
            "/swagger-ui",
            "/api/ws-chat",
            "/api/ws"
    );

    // 비로그인도 조회 가능하지만, 로그인 상태면 회원 정보를 같이 넘겨줘야 하는 경로
    // (예: 상품 목록/상세는 비로그인도 보이지만, 로그인 상태면 찜 여부 등을 함께 응답해야 함)
    // GET만 optional-auth로 통과되고, 같은 prefix라도 POST/PATCH/DELETE 등은 아래 기본 로직에 따라
    // 토큰이 없거나 유효하지 않으면 401로 막힘 (예: POST /api/v1/auctions/{id}/bids, /watch)
    private static final List<String> OPTIONAL_AUTH_GET_WHITELIST = List.of(
            "/api/products",  // 상품 목록/상세 (찜 여부 포함)
            "/api/v1/auctions"  // 경매 피드/상세/결과/입찰내역/관심여부 조회 (GET만 비로그인 허용)
    );

    // 회원 닉네임 조회 (GET /api/members/{id}/nickname)만 비로그인 허용 - "/api/members/" 전체를
    // optional-auth로 열면 GET /api/members/me(인증 필수)까지 같이 뚫리므로 정규식으로 정확히 한정
    private static final Pattern MEMBER_NICKNAME_PATTERN = Pattern.compile("^/api/members/[^/]+/nickname$");

    // 검색 관련 API는 GET/POST 모두 비로그인 허용 (검색/자동완성/인기검색어/검색어저장/임베딩 디버그).
    // 로그인 상태면 X-Member-Id를 같이 넘겨 개인화(검색 기록 반영)에 사용.
    // 단, 내 검색 기록 기반 추천(GET /api/search/recommendations/history)은 로그인 필수라 예외 처리.
    private static final String SEARCH_PATH_PREFIX = "/api/search";
    private static final String SEARCH_HISTORY_PATH = "/api/search/recommendations/history";

    // 인증된 사용자라도 ADMIN role이 아니면 막아야 하는 경로.
    // method == null이면 모든 메서드에 적용.
    private static final List<AdminOnlyRule> ADMIN_ONLY_RULES = List.of(
            new AdminOnlyRule(null, "/api/admin/"),
            new AdminOnlyRule(HttpMethod.PATCH, "/api/payments/deposits/adjust"),
            new AdminOnlyRule(HttpMethod.POST, "/api/payments/settlements/monthly"),
            new AdminOnlyRule(HttpMethod.POST, "/api/payments/settlements/pending-batch")
    );

    private record AdminOnlyRule(HttpMethod method, String pathPrefix) {
        boolean matches(HttpMethod requestMethod, String requestPath) {
            return (method == null || method.equals(requestMethod)) && requestPath.startsWith(pathPrefix);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod method = exchange.getRequest().getMethod();

        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        String token = resolveToken(exchange.getRequest());
        boolean optionalAuth = isOptionalAuthPath(method, path);

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

                    if (requiresAdmin(method, path) && !"ADMIN".equals(role)) {
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete();
                    }

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

    private boolean isOptionalAuthPath(HttpMethod method, String path) {
        if (path.startsWith(SEARCH_PATH_PREFIX) && !path.equals(SEARCH_HISTORY_PATH)) {
            return true;
        }
        if (!HttpMethod.GET.equals(method)) {
            return false;
        }
        return OPTIONAL_AUTH_GET_WHITELIST.stream().anyMatch(path::startsWith)
                || MEMBER_NICKNAME_PATTERN.matcher(path).matches();
    }

    private boolean requiresAdmin(HttpMethod method, String path) {
        return ADMIN_ONLY_RULES.stream().anyMatch(rule -> rule.matches(method, path));
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
