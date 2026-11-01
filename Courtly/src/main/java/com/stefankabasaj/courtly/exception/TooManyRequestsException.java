package com.stefankabasaj.courtly.exception;

import lombok.Getter;

@Getter
public class TooManyRequestsException extends RuntimeException {

    // sent to the client as the Retry-After header
    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
