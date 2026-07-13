package com.biddy.payment.global.exception;

public class PaymentRedisUnavailableException extends RuntimeException {

    public PaymentRedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
