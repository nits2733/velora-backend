package com.velora.backend.service.vision;

public record PreprocessedImage(byte[] bytes, String contentType, int width, int height) {
}
