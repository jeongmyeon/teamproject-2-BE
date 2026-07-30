package com.biddy.auction.auction.infra.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * WebSocket으로 클라이언트에 전송되는 경매 실시간 메시지 DTO
 *
 * [설계 원칙]
 * - 불변 객체 (Java 14 Record) 사용으로 스레드 안전성 보장
 * - Null 필드 자동 제거로 네트워크 트래픽 최적화
 * - 메시지 타입별 팩토리 메서드로 생성 실수 방지
 * - JSON 직렬화 최적화로 빠른 전송
 *
 * [메시지 타입]
 * 1. BID: 새로운 입찰 발생
 *    - 실시간 현재가 업데이트
 *    - 경쟁 강도 표시 (입찰 수)
 *    - 현재 1위 입찰자 정보
 *
 * 2. ENDED: 경매 종료
 *    - 낙찰: winnerId와 finalBid 포함
 *    - 유찰: 두 필드 모두 null
 *
 * [JSON 직렬화 예시]
 *
 * BID 메시지 (입찰 발생):
 * {
 *   "type": "BID",
 *   "currentBid": 750000,
 *   "bidCount": 8,
 *   "bidderId": 12345
 * }
 *
 * ENDED 메시지 (낙찰):
 * {
 *   "type": "ENDED",
 *   "winnerId": 12345,
 *   "finalBid": 750000
 * }
 *
 * ENDED 메시지 (유찰):
 * {
 *   "type": "ENDED"
 * }
 *
 * [@JsonInclude 어노테이션]
 * - NON_NULL: null 값인 필드는 JSON에서 제외
 * - 메시지 크기 30-40% 감소 효과
 * - 클라이언트의 undefined 처리 간소화
 *
 * [Record 사용 이점]
 * - 자동 equals(), hashCode(), toString() 구현
 * - 불변성 보장으로 동시성 문제 없음
 * - 컴파일 타임 타입 안정성
 * - 간결한 코드로 유지보수성 향상
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuctionWebSocketMessage(
        /**
         * 메시지 타입
         * - "BID": 새 입찰 발생
         * - "ENDED": 경매 종료
         */
        String type,

        /**
         * 현재 최고 입찰가 (BID 타입에서만 사용)
         * 클라이언트 화면의 현재가 표시 업데이트용
         */
        Long currentBid,

        /**
         * 누적 입찰 횟수 (BID 타입에서만 사용)
         * 경매 인기도 및 경쟁 강도 표시용
         */
        Integer bidCount,

        /**
         * 방금 입찰한 사용자 ID (BID 타입에서만 사용)
         * 현재 최고 입찰자 표시 및 알림용
         */
        Long bidderId,

        /**
         * 낙찰자 ID (ENDED 타입에서만 사용)
         * null인 경우 유찰을 의미
         */
        Long winnerId,

        /**
         * 최종 낙찰가 (ENDED 타입에서만 사용)
         * null인 경우 유찰을 의미
         */
        Long finalBid
) {

    /**
     * 입찰 발생 메시지 생성 팩토리 메서드
     *
     * [사용 시점]
     * - BidService에서 입찰 처리 성공 후
     * - 낙관적 락 재시도 성공 후
     * - 큐 시스템 처리 완료 후
     *
     * [클라이언트 처리]
     * - 현재가 표시 즉시 업데이트
     * - 입찰 수 카운터 증가
     * - "방금 입찰되었습니다" 토스트 알림
     * - 자신의 입찰인 경우 "입찰 성공" 표시
     * - 다른 사람 입찰인 경우 "경쟁자가 입찰했습니다" 표시
     *
     * @param currentBid 갱신된 현재 최고가
     * @param bidCount   총 입찰 횟수
     * @param bidderId   입찰자 ID
     * @return BID 타입 메시지 객체
     */
    public static AuctionWebSocketMessage bid(Long currentBid, Integer bidCount, Long bidderId) {
        // type: "BID", 입찰 관련 필드만 설정, 나머지는 null
        return new AuctionWebSocketMessage("BID", currentBid, bidCount, bidderId, null, null);
    }

    /**
     * 경매 정상 종료 (낙찰) 메시지 생성 팩토리 메서드
     *
     * [사용 시점]
     * - 스케줄러의 경매 종료 처리
     * - 즉시 구매 옵션 실행
     * - 판매자의 조기 종료
     *
     * [클라이언트 처리 - 낙찰자]
     * - "축하합니다! 낙찰되었습니다" 모달 팝업
     * - 결제 페이지 이동 버튼 표시
     * - 낙찰 내역 저장
     * - 푸시 알림 발송
     *
     * [클라이언트 처리 - 기타 참여자]
     * - "경매가 종료되었습니다" 안내
     * - 입찰 버튼 비활성화
     * - 유사 상품 추천
     * - 관심 상품 등록 유도
     *
     * @param winnerId 낙찰자 사용자 ID
     * @param finalBid 최종 낙찰 금액
     * @return ENDED 타입 메시지 객체
     */
    public static AuctionWebSocketMessage ended(Long winnerId, Long finalBid) {
        // type: "ENDED", 종료 관련 필드만 설정, 나머지는 null
        return new AuctionWebSocketMessage("ENDED", null, null, null, winnerId, finalBid);
    }

    /**
     * 경매 유찰 메시지 생성 팩토리 메서드
     *
     * [유찰 사유]
     * - 입찰자 없음
     * - 최저가 미달
     * - 판매자 취소
     * - 정책 위반으로 강제 종료
     *
     * [클라이언트 처리 - 판매자]
     * - "아쉽게도 유찰되었습니다" 안내
     * - 재등록 버튼 표시
     * - 시작가 조정 제안
     * - 프로모션 옵션 안내
     *
     * [클라이언트 처리 - 관심 등록자]
     * - "유찰되었습니다" 안내
     * - 재개 시 알림 설정 옵션
     * - 유사 상품 추천
     *
     * [서버 후속 처리]
     * - 상품 상태를 '판매 가능'으로 복원
     * - 유찰 통계 기록
     * - 재등록 프로세스 준비
     *
     * @return ENDED 타입 메시지 (winnerId, finalBid 모두 null)
     */
    public static AuctionWebSocketMessage unsold() {
        // type: "ENDED", 모든 데이터 필드 null (유찰 표시)
        return new AuctionWebSocketMessage("ENDED", null, null, null, null, null);
    }
}