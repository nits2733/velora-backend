package com.velora.backend.service.vision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.backend.config.VisionProperties;
import com.velora.backend.dto.vision.ColorPalette;
import com.velora.backend.dto.vision.VisionFeature;
import com.velora.backend.dto.vision.VisionJobResponse;
import com.velora.backend.dto.vision.VisionPreferences;
import com.velora.backend.dto.vision.VisionTransformAccepted;
import com.velora.backend.entity.vision.VisionJob;
import com.velora.backend.entity.vision.VisionJobStatus;
import com.velora.backend.exception.MediaUploadException;
import com.velora.backend.exception.RateLimitExceededException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.exception.VisionUnavailableException;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.repository.vision.VisionJobRepository;
import com.velora.backend.service.media.MediaUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VisionService {

    static final String INPUT_FOLDER = "vision/input";
    static final int MAX_STYLE_PROMPT = 300;
    static final int MAX_CUSTOM_COLOR = 60;
    static final int MAX_FEATURES = 10;
    private static final int MAX_IN_FLIGHT = 2;
    // Striped per-user locks (bounded memory); single-instance only, DB constraints would be needed across nodes.
    private static final Object[] LOCKS = java.util.stream.Stream.generate(Object::new).limit(64).toArray();

    private final VisionJobRepository jobRepository;
    private final UserRepository userRepository;
    private final MediaUploadService mediaUploadService;
    private final VisionJobProcessor processor;
    private final VisionProperties props;
    private final VisionPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final ImagePreprocessor imagePreprocessor;

    public VisionTransformAccepted submit(Long userId, MultipartFile image, String stylePrompt) {
        return submit(userId, image, stylePrompt, null);
    }

    /**
     * Deliberately not transactional: the Cloudinary upload must not hold a DB transaction open, and
     * the job row has to be committed (the repository save commits on return) before the async thread loads it.
     *
     * The duplicate check is DB-backed and runs twice: once before the upload (to avoid an orphan upload in the
     * common case) and again inside the in-memory per-user lock right before the save, so concurrent identical
     * requests on one instance cannot both create a job. Only multiple instances can still double-pass; true
     * multi-instance atomicity would need a DB advisory lock, which the no-@Query repository rule does not allow.
     */
    public VisionTransformAccepted submit(Long userId, MultipartFile image, String stylePrompt,
                                          VisionPreferences preferences) {
        if (stylePrompt != null && stylePrompt.length() > MAX_STYLE_PROMPT) {
            throw new IllegalArgumentException("stylePrompt must be at most 300 characters");
        }
        String legacyPrompt = blankToNull(VisionPromptBuilder.sanitise(stylePrompt, MAX_STYLE_PROMPT));
        VisionPreferences normalized = null;
        if (preferences != null) {
            normalized = validateAndNormalize(preferences);
        } else if (legacyPrompt == null) {
            throw new IllegalArgumentException("stylePrompt is required when no design preferences are supplied");
        }

        mediaUploadService.validate(image);
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new MediaUploadException("Failed to read uploaded file", e);
        }

        String preferencesJson = normalized == null ? null : toJson(normalized);
        String fingerprint = fingerprint(userId, bytes, preferencesJson, legacyPrompt);

        Optional<VisionJob> duplicate = findDuplicate(userId, fingerprint);
        if (duplicate.isPresent()) {
            return accepted(duplicate.get());
        }

        checkDailyLimit(userId);
        checkInFlight(userId);

        PreprocessedImage processed = imagePreprocessor.preprocess(bytes);
        byte[] processedBytes = processed.bytes();
        String contentType = processed.contentType();
        String inputUrl = mediaUploadService.uploadImageBytes(processedBytes, contentType, INPUT_FOLDER).secureUrl();

        VisionJob.VisionJobBuilder builder = VisionJob.builder()
                .user(userRepository.getReferenceById(userId))
                .inputImageUrl(inputUrl)
                .status(VisionJobStatus.PROCESSING)
                .requestFingerprint(fingerprint);
        if (normalized == null) {
            builder.stylePrompt(legacyPrompt);
        } else {
            builder.stylePrompt(legacyPrompt != null ? legacyPrompt : summary(normalized))
                    .preferences(preferencesJson)
                    .builtPrompt(promptBuilder.build(normalized).prompt())
                    .imageStrength(props.strengthFor(normalized.preservation()));
        }

        VisionJob job;
        synchronized (lockFor(userId)) {
            Optional<VisionJob> raced = findDuplicate(userId, fingerprint);
            if (raced.isPresent()) {
                return accepted(raced.get());
            }
            checkDailyLimit(userId);
            checkInFlight(userId);
            job = jobRepository.save(builder.createdAt(Instant.now()).build());
        }

        try {
            processor.process(job.getId(), processedBytes, contentType);
        } catch (TaskRejectedException e) {
            log.warn("Vision executor saturated; failing job {}", job.getId());
            job.setStatus(VisionJobStatus.FAILED);
            job.setError(VisionJobProcessor.FAILURE_MESSAGE);
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
            throw new VisionUnavailableException(VisionJobProcessor.FAILURE_MESSAGE);
        }
        return new VisionTransformAccepted(job.getId().toString(), VisionJobStatus.PROCESSING);
    }

    private Optional<VisionJob> findDuplicate(Long userId, String fingerprint) {
        return jobRepository.findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                userId, fingerprint, List.of(VisionJobStatus.PROCESSING, VisionJobStatus.COMPLETED),
                Instant.now().minus(props.getDuplicateWindow()));
    }

    private static VisionTransformAccepted accepted(VisionJob existing) {
        return new VisionTransformAccepted(existing.getId().toString(), existing.getStatus());
    }

    private void checkInFlight(Long userId) {
        long inFlight = jobRepository.countByUserIdAndStatusAndCreatedAtAfter(
                userId, VisionJobStatus.PROCESSING, Instant.now().minus(props.getStaleAfter()));
        if (inFlight >= MAX_IN_FLIGHT) {
            throw new RateLimitExceededException(
                    "You already have designs being generated. Please wait for them to finish.");
        }
    }

    private VisionPreferences validateAndNormalize(VisionPreferences p) {
        if (p.roomType() == null) {
            throw new IllegalArgumentException("roomType is required");
        }
        if (p.style() == null) {
            throw new IllegalArgumentException("style is required");
        }
        if (p.preservation() == null) {
            throw new IllegalArgumentException("preservation is required");
        }

        if (p.customColorDescription() != null && p.customColorDescription().length() > MAX_CUSTOM_COLOR) {
            throw new IllegalArgumentException("customColorDescription must be at most 60 characters");
        }
        String custom = blankToNull(VisionPromptBuilder.sanitise(p.customColorDescription(), MAX_CUSTOM_COLOR));
        if (p.colorPalette() == ColorPalette.CUSTOM) {
            if (custom == null) {
                throw new IllegalArgumentException("customColorDescription is required when colorPalette is CUSTOM");
            }
        } else if (custom != null) {
            throw new IllegalArgumentException("customColorDescription is only allowed when colorPalette is CUSTOM");
        }

        EnumSet<VisionFeature> features = EnumSet.noneOf(VisionFeature.class);
        if (p.features() != null) {
            if (p.features().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("features must not contain null values");
            }
            if (p.features().size() > MAX_FEATURES) {
                throw new IllegalArgumentException("At most 10 features are allowed");
            }
            features.addAll(p.features());
        }
        if (p.additionalInstructions() != null
                && p.additionalInstructions().length() > VisionPromptBuilder.MAX_FREE_TEXT) {
            throw new IllegalArgumentException("additionalInstructions must be at most 300 characters");
        }
        String extra = blankToNull(VisionPromptBuilder.sanitise(p.additionalInstructions(), VisionPromptBuilder.MAX_FREE_TEXT));

        return new VisionPreferences(p.roomType(), p.style(), p.colorPalette(),
                custom,
                p.budgetTier(), List.copyOf(features), p.preservation(),
                extra);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String toJson(VisionPreferences prefs) {
        try {
            return objectMapper.writeValueAsString(prefs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise vision preferences", e);
        }
    }

    private static String fingerprint(Long userId, byte[] imageBytes, String preferencesJson, String stylePrompt) {
        HexFormat hex = HexFormat.of();
        String material = userId + "\n" + hex.formatHex(sha256(imageBytes)) + "\n"
                + (preferencesJson == null ? "legacy" : preferencesJson) + "\n"
                + (stylePrompt == null ? "" : stylePrompt);
        return hex.formatHex(sha256(material.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String summary(VisionPreferences p) {
        String room = p.roomType().phrase();
        StringBuilder sb = new StringBuilder(Character.toUpperCase(room.charAt(0)) + room.substring(1));
        sb.append(" - ").append(p.style().phrase());
        if (!p.features().isEmpty()) {
            sb.append(" - ").append(p.features().stream().map(VisionFeature::name)
                    .map(n -> n.charAt(0) + n.substring(1).toLowerCase().replace('_', ' '))
                    .collect(Collectors.joining(", ")));
        }
        return sb.length() > MAX_STYLE_PROMPT ? sb.substring(0, MAX_STYLE_PROMPT) : sb.toString();
    }

    private void checkDailyLimit(Long userId) {
        long recent = jobRepository.countByUserIdAndStatusInAndCreatedAtAfter(
                userId, List.of(VisionJobStatus.PROCESSING, VisionJobStatus.COMPLETED),
                Instant.now().minus(Duration.ofHours(24)));
        if (recent >= props.getDailyLimit()) {
            throw new RateLimitExceededException("Daily design transformation limit reached. Please try again later.");
        }
    }

    private Object lockFor(Long userId) {
        return LOCKS[(int) Math.floorMod(userId, (long) LOCKS.length)];
    }

    @Transactional(readOnly = true)
    public VisionJobResponse get(Long userId, UUID id) {
        VisionJob job = jobRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Vision job not found"));
        return new VisionJobResponse(job.getId().toString(), job.getStatus(), job.getResultImageUrl(), job.getError());
    }
}
