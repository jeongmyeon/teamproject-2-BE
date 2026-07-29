package com.biddy.auction.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket + STOMP 프로토콜 설정
 *
 * [핵심 구성 요소]
 * 1. WebSocket: 양방향 실시간 통신 프로토콜 (HTTP 업그레이드)
 * 2. STOMP: Simple Text Oriented Messaging Protocol (메시지 라우팅)
 * 3. SockJS: WebSocket 폴백 라이브러리 (구형 브라우저 지원)
 * 4. Spring Message Broker: 인메모리 메시지 중계 서버
 *
 * [아키텍처 구조]
 * 클라이언트 → WebSocket 연결 → STOMP 핸드셰이크 → 채널 구독 → 실시간 메시지 수신
 *
 * [연결 과정]
 * 1. 클라이언트가 /ws 엔드포인트로 HTTP 요청
 * 2. HTTP → WebSocket 프로토콜 업그레이드 (101 Switching Protocols)
 * 3. STOMP CONNECT 프레임 전송
 * 4. CONNECTED 프레임 수신 (세션 ID 포함)
 * 5. SUBSCRIBE 프레임으로 채널 구독
 * 6. MESSAGE 프레임으로 실시간 데이터 수신
 *
 * [메시지 플로우]
 * - 서버 → 클라이언트: /topic/auctions/{auctionId} 채널로 브로드캐스트
 * - 클라이언트 → 서버: /app/* 경로로 메시지 전송 (현재 미사용)
 *
 * [구독 채널 구조]
 * /topic/auctions/{auctionId}
 * - topic: 1:N 브로드캐스트 (pub/sub 패턴)
 * - auctions: 도메인 구분자
 * - {auctionId}: 경매별 독립 채널
 *
 * [성능 최적화]
 * - 경매별 채널 분리로 불필요한 메시지 필터링 감소
 * - 인메모리 브로커로 낮은 지연시간 보장
 * - 하트비트로 유휴 연결 자동 정리
 *
 * [보안 고려사항]
 * - CORS 설정으로 허용된 Origin만 연결
 * - 인증/인가는 Spring Security와 통합 가능
 * - WSS (WebSocket Secure) 프로토콜 권장
 *
 * @Configuration: Spring 설정 클래스 선언
 * @EnableWebSocketMessageBroker: WebSocket 메시지 브로커 활성화
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * 메시지 브로커 설정
     *
     * [SimpleBroker vs External Broker]
     * - SimpleBroker: Spring 내장 인메모리 브로커
     *   - 장점: 설정 간단, 낮은 지연시간, 추가 인프라 불필요
     *   - 단점: 클러스터링 불가, 메시지 영속성 없음, 스케일아웃 제한
     *
     * - External Broker (RabbitMQ, ActiveMQ, Kafka):
     *   - 장점: 클러스터링, 메시지 영속성, 고급 라우팅
     *   - 단점: 추가 인프라 필요, 설정 복잡, 지연시간 증가
     *
     * [현재 설정]
     * - /topic: 구독 채널 prefix (1:N 브로드캐스트)
     *   - 예: /topic/auctions/12345 → 경매 12345 구독자 전체
     *   - 클라이언트는 SUBSCRIBE 프레임으로 구독 시작
     *
     * - /app: 애플리케이션 메시지 prefix (클라이언트 → 서버)
     *   - 예: /app/bid → @MessageMapping("/bid") 메서드로 라우팅
     *   - 현재는 REST API로 처리하므로 미사용
     *
     * [확장 옵션]
     * - /queue: 1:1 메시지용 (개인 알림)
     * - /user: 특정 사용자 전용 채널
     * - 하트비트 설정: 연결 상태 모니터링
     * - 태스크 스케줄러: 메시지 처리 스레드풀 설정
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 구독 채널 prefix 설정
        // 클라이언트가 이 prefix로 시작하는 채널 구독 가능
        config.enableSimpleBroker("/topic");

        // 애플리케이션 메시지 prefix 설정
        // 클라이언트가 서버로 메시지 전송 시 사용 (현재 미사용)
        config.setApplicationDestinationPrefixes("/app");

        // [선택적 설정 예시]
        // config.enableSimpleBroker("/topic", "/queue")
        //     .setHeartbeatValue(new long[] {10000, 10000})  // 하트비트 10초
        //     .setTaskScheduler(taskScheduler());  // 커스텀 스케줄러
    }

    /**
     * STOMP 엔드포인트 등록
     *
     * [WebSocket 엔드포인트]
     * - /ws: 순수 WebSocket 연결용
     *   - 최신 브라우저 및 네이티브 앱용
     *   - JavaScript: new WebSocket('ws://localhost:8080/ws')
     *   - STOMP.js 라이브러리 사용 시
     *
     * - /ws-sockjs: SockJS 폴백 연결용
     *   - 구형 브라우저 (IE9 이하) 지원
     *   - 프록시/방화벽 우회
     *   - 자동 폴백: WebSocket → HTTP Streaming → HTTP Long Polling
     *
     * [CORS 설정]
     * - setAllowedOriginPatterns("*"): 모든 Origin 허용 (개발용)
     * - 운영 환경 권장 설정:
     *   - setAllowedOrigins("https://biddy.com")
     *   - setAllowedOriginPatterns("https://*.biddy.com")
     *
     * [연결 URL 예시]
     * - 로컬: ws://localhost:8080/ws
     * - 운영: wss://api.biddy.com/ws (HTTPS 환경에서는 WSS 필수)
     * - SockJS: http://localhost:8080/ws-sockjs
     *
     * [클라이언트 연결 코드 예시]
     * ```javascript
     * // 순수 WebSocket + STOMP
     * const socket = new WebSocket('ws://localhost:8080/ws');
     * const stompClient = Stomp.over(socket);
     *
     * // SockJS 사용
     * const socket = new SockJS('http://localhost:8080/ws-sockjs');
     * const stompClient = Stomp.over(socket);
     *
     * // 연결 및 구독
     * stompClient.connect({}, function(frame) {
     *     stompClient.subscribe('/topic/auctions/12345', function(message) {
     *         const data = JSON.parse(message.body);
     *         console.log('New bid:', data);
     *     });
     * });
     * ```
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 순수 WebSocket 엔드포인트
        // 최신 브라우저 및 네이티브 앱에서 사용
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");  // 개발용 - 운영 시 제한 필요

        // SockJS 폴백 엔드포인트
        // WebSocket 미지원 환경 자동 감지 및 대체 전송 방식 선택
        registry.addEndpoint("/ws-sockjs")
                .setAllowedOriginPatterns("*")  // 개발용 - 운영 시 제한 필요
                .withSockJS();  // SockJS 프로토콜 활성화

        // [추가 설정 옵션]
        // .setHandshakeHandler(customHandshakeHandler)  // 커스텀 핸드셰이크
        // .addInterceptors(customInterceptor)  // 연결 인터셉터
        // .setClientLibraryUrl(...)  // SockJS 클라이언트 라이브러리 URL
    }
}