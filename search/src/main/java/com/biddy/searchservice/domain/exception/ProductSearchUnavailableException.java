package com.biddy.searchservice.domain.exception;

public class ProductSearchUnavailableException extends RuntimeException {

    public ProductSearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
