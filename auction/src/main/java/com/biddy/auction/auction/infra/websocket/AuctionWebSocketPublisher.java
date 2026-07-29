package com.biddy.auction.auction.infra.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 경매 WebSocket 메시지 발행기
 *
 * [핵심 역할]
 * - Spring WebSocket + STOMP를 통한 실시간 경매 정보 브로드캐스팅
 * - 입찰, 경매 종료 등 이벤트를 구독 클라이언트에게 실시간 전달
 * - pub/sub 패턴으로 다수의 클라이언트에게 동시 알림
 *
 * [메시지 채널 구조]
 * - 경로: /topic/auctions/{auctionId}
 * - topic: 1:N 브로드캐스트용 (모든 구독자에게 전송)
 * - 경매별 독립 채널로 분리하여 불필요한 메시지 수신 방지
 *
 * [메시지 타입]
 * 1. BID: 새로운 입찰 발생
 *    - 현재 최고가 업데이트
 *    - 총 입찰 수 증가
 *    - 현재 최고 입찰자 정보
 *
 * 2. ENDED: 경매 정상 종료 (낙찰)
 *    - 최종 낙찰자 정보
 *    - 최종 낙찰 금액
 *
 * 3. UNSOLD: 경매 유찰
 *    - 입찰자 없이 종료된 경우
 *
 * [성능 최적화]
 * - 비동기 메시지 전송으로 입찰 처리 성능 영향 최소화
 * - 채널별 구독 관리로 불필요한 네트워크 트래픽 감소
 * - 메시지 크기 최적화 (필수 정보만 전송)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuctionWebSocketPublisher {

    /**
     * Spring WebSocket 메시징 템플릿
     *
     * STOMP 프로토콜을 통해 메시지를 전송하는 핵심 컴포넌트
     * 내부적으로 메시지 브로커와 연동하여 구독자에게 전달
     */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 입찰 발생 메시지 브로드캐스트
     *
     * [호출 시점]
     * - 입찰이 성공적으로 처리된 직후
     * - 낙관적 락 재시도 성공 후
     * - 큐 시스템에서 지연 처리 완료 후
     *
     * [메시지 전달 과정]
     * 1. BidService에서 입찰 처리 완료
     * 2. publishBid() 메서드 호출
     * 3. AuctionWebSocketMessage 객체 생성 (type: BID)
     * 4. /topic/auctions/{auctionId} 채널로 전송
     * 5. 해당 채널 구독 중인 모든 클라이언트에게 전달
     * 6. 클라이언트 UI 실시간 업데이트
     *
     * [네트워크 최적화]
     * - 메시지는 JSON 형식으로 직렬화
     * - 필수 필드만 포함하여 패킷 크기 최소화
     * - 비동기 전송으로 블로킹 없음
     *
     * @param auctionId  경매 식별자 (채널 구분용)
     * @param currentBid 갱신된 현재 최고가 (화면 표시용)
     * @param bidCount   누적 입찰 수 (경쟁 강도 표시)
     * @param bidderId   최고 입찰자 ID (현재 1등 표시)
     */
    public void publishBid(String auctionId, Long currentBid, Integer bidCount, Long bidderId) {
        // BID 타입 메시지 생성
        // 내부적으로 type 필드를 "BID"로 설정
        AuctionWebSocketMessage message = AuctionWebSocketMessage.bid(currentBid, bidCount, bidderId);

        // STOMP 목적지 경로 생성
        // 형식: /topic/auctions/AUCTION_ID_12345
        String destination = "/topic/auctions/" + auctionId;

        // 메시지 전송 (비동기)
        // SimpMessagingTemplate이 내부적으로 다음 처리:
        // 1. 메시지를 JSON으로 변환 (Jackson 사용)
        // 2. STOMP 프레임 생성
        // 3. 구독자 목록 조회
        // 4. 각 구독자의 WebSocket 세션으로 전송
        messagingTemplate.convertAndSend(destination, message);

        // 디버그 로깅
        // 운영 환경에서는 성능을 위해 DEBUG 레벨 비활성화 권장
        log.debug("WebSocket BID 메시지 발행 - 경매: {}, 현재가: {}원, 입찰수: {}",
                auctionId, currentBid, bidCount);
    }

    /**
     * 경매 정상 종료 메시지 브로드캐스트 (낙찰)
     *
     * [호출 시점]
     * - 경매 종료 시간 도달 (스케줄러)
     * - 즉시 구매 발생
     * - 판매자가 조기 종료
     *
     * [클라이언트 처리]
     * - "축하합니다" 팝업 표시 (낙찰자)
     * - "경매가 종료되었습니다" 안내 (기타 참여자)
     * - 입찰 버튼 비활성화
     * - 결제 페이지로 이동 버튼 표시 (낙찰자)
     *
     * [데이터 정합성]
     * - DB 트랜잭션 커밋 후 호출
     * - 메시지 전송 실패 시에도 DB 상태는 유지
     * - 클라이언트는 재접속 시 REST API로 상태 확인
     *
     * @param auctionId 종료된 경매 ID
     * @param winnerId  낙찰자 사용자 ID (null 가능)
     * @param finalBid  최종 낙찰 금액
     */
    public void publishEnded(String auctionId, Long winnerId, Long finalBid) {
        // ENDED 타입 메시지 생성
        AuctionWebSocketMessage message = AuctionWebSocketMessage.ended(winnerId, finalBid);

        // 목적지 설정
        String destination = "/topic/auctions/" + auctionId;

        // 종료 메시지 전송
        messagingTemplate.convertAndSend(destination, message);

        // 정보 로깅 (중요 이벤트이므로 INFO 레벨)
        log.info("WebSocket ENDED 메시지 발행 - 경매: {}, 낙찰자: {}, 낙찰가: {}원",
                auctionId, winnerId, finalBid);
    }

    /**
     * 경매 유찰 메시지 브로드캐스트
     *
     * [유찰 조건]
     * - 경매 종료 시점까지 입찰자 없음
     * - 최저가 미달
     * - 판매자가 취소
     *
     * [클라이언트 처리]
     * - "유찰되었습니다" 안내 메시지
     * - 재등록 안내 (판매자)
     * - 유사 상품 추천 (구매 희망자)
     *
     * [후속 처리]
     * - 상품 상태를 다시 판매 가능으로 변경
     * - 판매자에게 재등록 알림 발송
     * - 관심 등록자에게 재개 알림 옵션
     *
     * @param auctionId 유찰된 경매 ID
     */
    public void publishUnsold(String auctionId) {
        // UNSOLD 타입 메시지 생성
        // winnerId와 finalBid가 null인 특별한 ENDED 메시지
        AuctionWebSocketMessage message = AuctionWebSocketMessage.unsold();

        // 목적지 설정
        String destination = "/topic/auctions/" + auctionId;

        // 유찰 메시지 전송
        messagingTemplate.convertAndSend(destination, message);

        // 정보 로깅
        log.info("WebSocket UNSOLD 메시지 발행 - 경매: {} 유찰", auctionId);
    }
}