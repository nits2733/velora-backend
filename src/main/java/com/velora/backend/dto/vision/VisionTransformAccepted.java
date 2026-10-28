package com.velora.backend.dto.vision;

import com.velora.backend.entity.vision.VisionJobStatus;

public record VisionTransformAccepted(String id, VisionJobStatus status) {
}
