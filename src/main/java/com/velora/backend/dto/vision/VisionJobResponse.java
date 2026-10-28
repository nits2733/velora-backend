package com.velora.backend.dto.vision;

import com.velora.backend.entity.vision.VisionJobStatus;

public record VisionJobResponse(String id, VisionJobStatus status, String resultImageUrl, String error) {
}
