package com.velora.backend.repository.vision;

import com.velora.backend.entity.vision.VisionJob;
import com.velora.backend.entity.vision.VisionJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VisionJobRepository extends JpaRepository<VisionJob, UUID> {

    long countByUserIdAndStatusInAndCreatedAtAfter(Long userId, Collection<VisionJobStatus> statuses, Instant after);

    long countByUserIdAndStatusAndCreatedAtAfter(Long userId, VisionJobStatus status, Instant after);

    Optional<VisionJob> findByIdAndUserId(UUID id, Long userId);

    Optional<VisionJob> findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
            Long userId, String requestFingerprint, Collection<VisionJobStatus> statuses, Instant after);

    List<VisionJob> findByStatusAndCreatedAtBefore(VisionJobStatus status, Instant before);
}
