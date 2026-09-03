package com.velora.backend.exception;

public class ResendCooldownException extends RuntimeException {
    public ResendCooldownException(String message) {
        super(message);
    }
}
