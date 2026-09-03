package com.velora.backend.exception;

public class MaxOtpAttemptsException extends RuntimeException {
    public MaxOtpAttemptsException(String message) {
        super(message);
    }
}
