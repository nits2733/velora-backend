package com.velora.backend.scheduler;

import com.velora.backend.config.VisionProperties;
import com.velora.backend.entity.vision.VisionJob;
import com.velora.backend.entity.vision.VisionJobStatus;
import com.velora.backend.repository.vision.VisionJobRepository;
import com.velora.backend.service.vision.VisionJobProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class VisionJobSweeper {

    private final VisionJobRepository jobRepository;
    private final VisionProperties props;

    // Deliberately not @Transactional: each save must commit on its own so an optimistic-lock loss surfaces per job.
    @Scheduled(fixedRate = 300000)
    public void failStaleJobs() {
        Instant now = Instant.now();
        List<VisionJob> stale = jobRepository.findByStatusAndCreatedAtBefore(
                VisionJobStatus.PROCESSING, now.minus(props.getStaleAfter()));
        for (VisionJob job : stale) {
            job.setStatus(VisionJobStatus.FAILED);
            job.setError(VisionJobProcessor.FAILURE_MESSAGE);
            job.setCompletedAt(now);
            try {
                jobRepository.save(job);
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("Vision job {} changed concurrently; skipping", job.getId());
            }
        }
        if (!stale.isEmpty()) {
            log.warn("Marked {} stale vision jobs as FAILED", stale.size());
        }
    }
}
