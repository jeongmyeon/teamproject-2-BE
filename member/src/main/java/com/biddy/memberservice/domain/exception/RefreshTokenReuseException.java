package com.biddy.memberservice.domain.exception;

public class RefreshTokenReuseException extends RuntimeException {

    public RefreshTokenReuseException(String message) {
        super(message);
    }
}
