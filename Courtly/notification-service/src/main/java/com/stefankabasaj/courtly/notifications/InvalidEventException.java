package com.stefankabasaj.courtly.notifications;

// a message that will never make sense, however often it's retried - goes straight to the DLT
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
