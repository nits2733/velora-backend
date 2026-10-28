package com.velora.backend.exception;

public class VisionProviderException extends RuntimeException {

    private final VisionFailureCategory category;

    public VisionProviderException(String message) {
        this(message, VisionFailureCategory.GENERIC, null);
    }

    public VisionProviderException(String message, Throwable cause) {
        this(message, VisionFailureCategory.GENERIC, cause);
    }

    public VisionProviderException(String message, VisionFailureCategory category, Throwable cause) {
        super(message, cause);
        this.category = category == null ? VisionFailureCategory.GENERIC : category;
    }

    public VisionFailureCategory category() {
        return category;
    }
}
