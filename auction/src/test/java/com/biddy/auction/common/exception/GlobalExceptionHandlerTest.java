package com.biddy.auction.common.exception;

import com.biddy.auction.auction.domain.model.Auction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("일반 낙관적 락 충돌은 409 E003으로 변환한다")
    void optimisticLockFailure_returnsConflict() {
        ResponseEntity<ErrorResponse> response = handler.handleConcurrentModification(
                new ObjectOptimisticLockingFailureException(Auction.class, "A-001"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("E003");
    }

    @Test
    @DisplayName("일시적인 데이터 저장소 장애는 503 S002로 변환한다")
    void dataStoreFailure_returnsServiceUnavailable() {
        ResponseEntity<ErrorResponse> response = handler.handleServiceUnavailable(
                new DataAccessResourceFailureException("temporarily unavailable"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("S002");
    }
}
