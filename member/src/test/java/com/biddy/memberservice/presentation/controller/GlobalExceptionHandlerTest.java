package com.biddy.memberservice.presentation.controller;

import com.biddy.memberservice.domain.exception.ErrorResponse;
import com.biddy.memberservice.domain.exception.RefreshTokenReuseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void RefreshTokenReuseException은_401로_매핑된다() {
        RefreshTokenReuseException exception =
                new RefreshTokenReuseException("비정상적인 토큰 재사용이 감지되어 모든 세션이 종료되었습니다. 다시 로그인해 주세요.");

        ResponseEntity<ErrorResponse> response = handler.handleRefreshTokenReuseException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getStatus()).isEqualTo(401);
        assertThat(response.getBody().getMessage()).isEqualTo(exception.getMessage());
    }
}
