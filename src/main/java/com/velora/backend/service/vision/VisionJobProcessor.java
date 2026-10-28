package com.velora.backend.service.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.backend.config.VisionProperties;
import com.velora.backend.dto.vision.Preservation;
import com.velora.backend.entity.vision.VisionJob;
import com.velora.backend.entity.vision.VisionJobStatus;
import com.velora.backend.exception.VisionFailureCategory;
import com.velora.backend.exception.VisionProviderException;
import com.velora.backend.repository.vision.VisionJobRepository;
import com.velora.backend.service.media.MediaUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.UUID;

/**
 * Runs on the vision executor thread, outside any request transaction. Each state change is persisted
 * through the repository's own transaction so the polling endpoint sees it immediately.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VisionJobProcessor {

    public static final String FAILURE_MESSAGE = "We couldn't generate your design right now. Please try again.";
    public static final String MODERATION_MESSAGE = "We couldn't process this photo. Please try a different image.";
    public static final String UNAVAILABLE_MESSAGE =
            "The design service is busy right now. Please try again in a few minutes.";
    static final String RESULT_FOLDER = "vision/result";
    private static final ObjectMapper PREFS_MAPPER = new ObjectMapper();

    private final VisionJobRepository jobRepository;
    private final VisionProvider visionProvider;
    private final MediaUploadService mediaUploadService;
    private final VisionProperties props;
    private final VisionPromptBuilder promptBuilder;

    @Async("visionExecutor")
    public void process(UUID jobId, byte[] imageBytes, String contentType) {
        VisionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != VisionJobStatus.PROCESSING) {
            log.warn("Vision job {} missing or no longer PROCESSING; skipping", jobId);
            return;
        }
        try {
            boolean structured = job.getBuiltPrompt() != null;
            String prompt = structured ? job.getBuiltPrompt() : props.getStylePrefix() + job.getStylePrompt();
            String negativePrompt = structured ? promptBuilder.negativePrompt(preservationOf(job)) : null;
            double strength = job.getImageStrength() != null ? job.getImageStrength() : props.getImageStrength();
            Long seed = job.getRequestFingerprint() == null
                    ? null : VisionSeed.fromFingerprint(job.getRequestFingerprint());
            byte[] result = visionProvider.transform(imageBytes, contentType, prompt, negativePrompt, strength, seed);
            String url = mediaUploadService.uploadImageBytes(result, "image/png", RESULT_FOLDER).secureUrl();
            job.setResultImageUrl(url);
            job.setStatus(VisionJobStatus.COMPLETED);
            job.setError(null);
        } catch (Exception e) {
            log.warn("Vision job {} failed: {}", jobId, e.getClass().getSimpleName());
            job.setStatus(VisionJobStatus.FAILED);
            job.setError(e instanceof VisionProviderException vpe ? messageFor(vpe.category()) : FAILURE_MESSAGE);
        }
        job.setCompletedAt(Instant.now());
        try {
            jobRepository.save(job);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Vision job {} was changed concurrently (likely swept as stale); dropping result", jobId);
        }
    }

    private static Preservation preservationOf(VisionJob job) {
        try {
            return Preservation.valueOf(PREFS_MAPPER.readTree(job.getPreferences()).path("preservation").asText());
        } catch (Exception e) {
            return Preservation.BALANCED;
        }
    }

    private static String messageFor(VisionFailureCategory category) {
        return switch (category) {
            case MODERATION -> MODERATION_MESSAGE;
            case UNAVAILABLE -> UNAVAILABLE_MESSAGE;
            case GENERIC -> FAILURE_MESSAGE;
        };
    }
}
