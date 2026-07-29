package com.biddy.auction.common.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 전역 예외 처리 핸들러.
 *
 * <p>{@code @RestControllerAdvice}로 모든 Controller의 예외를
 * 가로채어 일관된 {@code ErrorResponse} 형식으로 반환한다.</p>
 *
 * <p>처리 우선순위:</p>
 * <ol>
 *   <li>BusinessException — 비즈니스 규칙 위반 (4xx)</li>
 *   <li>MethodArgumentTypeMismatchException — 잘못된 파라미터 타입 (400)</li>
 *   <li>Exception — 예상치 못한 서버 오류 (500)</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외 처리.
     * ErrorCode에 정의된 HTTP 상태 코드와 메시지를 그대로 반환한다.
     */
    @ExceptionHandler(BusinessException.class)
    protected ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        if (e.getErrorCode().getStatus().is5xxServerError()) {
            log.error("BusinessException: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        } else {
            log.warn("BusinessException: code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        }
        ErrorResponse response = ErrorResponse.of(e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getStatus()).body(response);
    }

    /** 필수 인증 헤더 누락을 401로 반환한다. */
    @ExceptionHandler(MissingRequestHeaderException.class)
    protected ResponseEntity<ErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException e) {
        log.warn("Missing request header: {}", e.getHeaderName());
        ErrorCode errorCode = "X-Member-Id".equalsIgnoreCase(e.getHeaderName())
                ? ErrorCode.AUTHENTICATION_REQUIRED
                : ErrorCode.INVALID_INPUT;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    /** 요청 본문의 Bean Validation 실패를 처리한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        boolean invalidBidAmount = e.getBindingResult().getFieldErrors().stream()
                .anyMatch(fieldError -> "amount".equals(fieldError.getField()));

        ErrorCode errorCode = invalidBidAmount
                ? ErrorCode.INVALID_BID_AMOUNT
                : ErrorCode.INVALID_INPUT;
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse(errorCode.getMessage());

        log.warn("Request validation failed: {}", message);
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, message));
    }

    /** 잘못된 JSON, 쿼리 파라미터와 메서드 파라미터 검증 실패를 400으로 반환한다. */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            ConstraintViolationException.class,
            HandlerMethodValidationException.class
    })
    protected ResponseEntity<ErrorResponse> handleInvalidInput(Exception e) {
        log.warn("Invalid request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }

    /**
     * 파라미터 타입 불일치 처리.
     * 예: status=INVALID 같은 잘못된 Enum 값 전달 시 발생.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    protected ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("TypeMismatch: parameter={}, value={}", e.getName(), e.getValue());
        String message = String.format("'%s' 파라미터 값 '%s'이(가) 유효하지 않습니다", e.getName(), e.getValue());
        ErrorResponse response = ErrorResponse.of(ErrorCode.INVALID_INPUT, message);
        return ResponseEntity.badRequest().body(response);
    }

    /** 입찰 이외의 Auction 갱신에서 발생한 낙관적 락 충돌을 409로 반환한다. */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    protected ResponseEntity<ErrorResponse> handleConcurrentModification(Exception e) {
        log.warn("Concurrent resource modification", e);
        ErrorCode errorCode = ErrorCode.RESOURCE_CONCURRENT_MODIFICATION;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    /** DB 또는 Redis 연결 실패와 타임아웃을 재시도 가능한 503으로 반환한다. */
    @ExceptionHandler({
            DataAccessResourceFailureException.class,
            QueryTimeoutException.class,
            CannotCreateTransactionException.class,
            TransactionTimedOutException.class
    })
    protected ResponseEntity<ErrorResponse> handleServiceUnavailable(Exception e) {
        log.error("Temporary infrastructure failure", e);
        ErrorCode errorCode = ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    /** 예상하지 못한 DB 제약조건 위반을 데이터 정합성 오류로 처리한다. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    protected ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.error("Data integrity violation", e);
        ErrorCode errorCode = ErrorCode.DATA_INTEGRITY_ERROR;
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    /**
     * 예상치 못한 서버 오류 처리.
     * 에러 로그를 남기고 클라이언트에는 상세 정보를 노출하지 않는다.
     */
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unhandled exception", e);
        ErrorResponse response = ErrorResponse.of(ErrorCode.INTERNAL_ERROR);
        return ResponseEntity.internalServerError().body(response);
    }
}
