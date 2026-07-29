package com.biddy.auction.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 애플리케이션 전역 에러 코드.
 *
 * <p>HTTP 상태 코드와 비즈니스 에러 코드를 매핑하여
 * 일관된 에러 응답을 제공한다.</p>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400 Bad Request
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "E001", "입력값이 유효하지 않습니다"),

    // 404 Not Found
    AUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "A001", "경매를 찾을 수 없습니다"),
    BID_NOT_FOUND(HttpStatus.NOT_FOUND, "B001", "입찰 내역을 찾을 수 없습니다"),

    // 400 Bad Request
    BID_AMOUNT_TOO_LOW(HttpStatus.BAD_REQUEST, "B002", "최소 입찰 단위 미달입니다"),

    // 403 Forbidden
    SELF_BID_NOT_ALLOWED(HttpStatus.FORBIDDEN, "B003", "본인 경매에는 입찰할 수 없습니다"),

    // 403 Forbidden
    NOT_AUCTION_OWNER(HttpStatus.FORBIDDEN, "A004", "본인의 경매만 종료할 수 있습니다"),

    // 409 Conflict
    AUCTION_ALREADY_ENDED(HttpStatus.CONFLICT, "A002", "이미 종료된 경매입니다"),
    AUCTION_STILL_LIVE(HttpStatus.CONFLICT, "A003", "아직 진행 중인 경매입니다"),
    BID_CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "B004", "동시 입찰 충돌이 발생했습니다. 최신 가격을 확인한 후 다시 시도해주세요"),

    // 500 Internal Server Error
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "S001", "서버 내부 오류가 발생했습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
