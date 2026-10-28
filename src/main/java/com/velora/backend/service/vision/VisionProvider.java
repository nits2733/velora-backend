package com.velora.backend.service.vision;

public interface VisionProvider {

    double DEFAULT_STRENGTH = 0.35;

    /** A null seed lets the provider pick one; a fixed seed makes the call reproducible where the provider supports it. */
    byte[] transform(byte[] inputImage, String contentType, String prompt, String negativePrompt, double strength,
                     Long seed);

    default byte[] transform(byte[] inputImage, String contentType, String prompt, String negativePrompt,
                             double strength) {
        return transform(inputImage, contentType, prompt, negativePrompt, strength, null);
    }

    default byte[] transform(byte[] inputImage, String contentType, String prompt) {
        return transform(inputImage, contentType, prompt, null, DEFAULT_STRENGTH, null);
    }
}
