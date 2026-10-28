package com.velora.backend.service.vision;

import com.velora.backend.config.VisionProperties;
import com.velora.backend.dto.media.MediaUploadResponse;
import com.velora.backend.entity.vision.VisionJob;
import com.velora.backend.entity.vision.VisionJobStatus;
import com.velora.backend.exception.MediaUploadException;
import com.velora.backend.exception.VisionFailureCategory;
import com.velora.backend.exception.VisionProviderException;
import com.velora.backend.repository.vision.VisionJobRepository;
import com.velora.backend.service.media.MediaUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisionJobProcessorTest {

    private static final String SAFE_ERROR = "We couldn't generate your design right now. Please try again.";
    private static final String PREFIX = "interior design photo, same room layout, ";
    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final byte[] INPUT = "in".getBytes();
    private static final byte[] OUTPUT = "out".getBytes();

    @Mock private VisionJobRepository visionJobRepository;
    @Mock private VisionProvider visionProvider;
    @Mock private MediaUploadService mediaUploadService;
    @Mock private VisionPromptBuilder promptBuilder;

    private VisionProperties props;
    private VisionJobProcessor processor;
    private VisionJob job;

    @BeforeEach
    void setUp() {
        props = new VisionProperties();
        props.setStylePrefix(PREFIX);
        processor = new VisionJobProcessor(visionJobRepository, visionProvider, mediaUploadService, props, promptBuilder);
        job = VisionJob.builder().id(JOB_ID).stylePrompt("scandinavian").status(VisionJobStatus.PROCESSING)
                .createdAt(Instant.now()).build();
    }

    private MediaUploadResponse uploaded(String url) {
        return new MediaUploadResponse(url, "pid", "png", 3L, 1, 1);
    }

    @Test
    void process_success_completesJobWithResultUrl() {
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), anyString(), any(), anyDouble(), any())).thenReturn(OUTPUT);
        when(mediaUploadService.uploadImageBytes(eq(OUTPUT), any(), eq("vision/result")))
                .thenReturn(uploaded("https://cdn.velora.test/out.png"));

        processor.process(JOB_ID, INPUT, "image/jpeg");

        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.COMPLETED);
        assertThat(job.getResultImageUrl()).isEqualTo("https://cdn.velora.test/out.png");
        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getError()).isNull();
        verify(visionJobRepository).save(job);
    }

    @Test
    void process_buildsPromptFromPrefixPlusStylePrompt() {
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        lenient().when(promptBuilder.negativePrompt(any())).thenReturn("should-not-be-used");
        when(visionProvider.transform(INPUT, "image/jpeg", PREFIX + "scandinavian", null, props.getImageStrength(), null)).thenReturn(OUTPUT);
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/result")))
                .thenReturn(uploaded("https://cdn.velora.test/o.png"));

        processor.process(JOB_ID, INPUT, "image/jpeg");

        verify(visionProvider).transform(INPUT, "image/jpeg", PREFIX + "scandinavian", null, 0.35, null);
    }

    @Test
    void process_structuredJob_passesBuiltPromptNegativeAndStrengthToProvider() {
        job.setBuiltPrompt("BUILT PROMPT");
        job.setImageStrength(0.7);
        when(promptBuilder.negativePrompt(any())).thenReturn("distorted, warped");
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(INPUT, "image/jpeg", "BUILT PROMPT", "distorted, warped", 0.7, null)).thenReturn(OUTPUT);
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/result")))
                .thenReturn(uploaded("https://cdn.velora.test/o.png"));

        processor.process(JOB_ID, INPUT, "image/jpeg");

        verify(visionProvider).transform(INPUT, "image/jpeg", "BUILT PROMPT", "distorted, warped", 0.7, null);
        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.COMPLETED);
    }

    @Test
    void process_structuredJob_doesNotPrependStylePrefix() {
        job.setBuiltPrompt("BUILT PROMPT");
        job.setImageStrength(0.5);
        lenient().when(promptBuilder.negativePrompt(any())).thenReturn(null);
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), anyString(), any(), anyDouble(), any())).thenReturn(OUTPUT);
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/result")))
                .thenReturn(uploaded("https://cdn.velora.test/o.png"));

        processor.process(JOB_ID, INPUT, "image/jpeg");

        verify(visionProvider).transform(eq(INPUT), eq("image/jpeg"), eq("BUILT PROMPT"), isNull(), eq(0.5), isNull());
    }

    @Test
    void process_structuredJob_nullStrengthFallsBackToConfiguredDefault() {
        job.setBuiltPrompt("BUILT PROMPT");
        props.setImageStrength(0.4);
        lenient().when(promptBuilder.negativePrompt(any())).thenReturn(null);
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), anyString(), any(), anyDouble(), any())).thenReturn(OUTPUT);
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/result")))
                .thenReturn(uploaded("https://cdn.velora.test/o.png"));

        processor.process(JOB_ID, INPUT, "image/jpeg");

        verify(visionProvider).transform(eq(INPUT), eq("image/jpeg"), eq("BUILT PROMPT"), isNull(), eq(0.4), isNull());
    }

    @Test
    void process_legacyJob_usesJobStrengthWhenPresentButNoNegativePrompt() {
        job.setImageStrength(0.6);
        lenient().when(promptBuilder.negativePrompt(any())).thenReturn("nope");
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), anyString(), any(), anyDouble(), any())).thenReturn(OUTPUT);
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/result")))
                .thenReturn(uploaded("https://cdn.velora.test/o.png"));

        processor.process(JOB_ID, INPUT, "image/jpeg");

        verify(visionProvider).transform(eq(INPUT), eq("image/jpeg"), eq(PREFIX + "scandinavian"), isNull(), eq(0.6), isNull());
    }

    @Test
    void process_providerFailure_marksFailedWithSafeMessageAndNeverLeaksDetail() {
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), any(), any(), anyDouble(), any()))
                .thenThrow(new VisionProviderException("api_key=secret123 provider body"));

        assertThatCode(() -> processor.process(JOB_ID, INPUT, "image/jpeg")).doesNotThrowAnyException();

        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.FAILED);
        assertThat(job.getError()).isEqualTo(SAFE_ERROR);
        assertThat(job.getError()).doesNotContain("secret123").doesNotContain("provider body");
        assertThat(job.getResultImageUrl()).isNull();
        verify(visionJobRepository).save(job);
        verifyNoInteractions(mediaUploadService);
    }

    @Test
    void process_uploadFailure_marksFailedWithSafeMessage() {
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), any(), any(), anyDouble(), any())).thenReturn(OUTPUT);
        when(mediaUploadService.uploadImageBytes(any(), any(), anyString()))
                .thenThrow(new MediaUploadException("cloudinary api_key=secret123 exploded"));

        assertThatCode(() -> processor.process(JOB_ID, INPUT, "image/jpeg")).doesNotThrowAnyException();

        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.FAILED);
        assertThat(job.getError()).isEqualTo(SAFE_ERROR);
        assertThat(job.getResultImageUrl()).isNull();
    }

    @Test
    void process_unexpectedRuntimeException_marksFailed() {
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), any(), any(), anyDouble(), any())).thenThrow(new IllegalStateException("missing key"));

        assertThatCode(() -> processor.process(JOB_ID, INPUT, "image/jpeg")).doesNotThrowAnyException();

        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.FAILED);
        assertThat(job.getError()).isEqualTo(SAFE_ERROR);
        assertThat(job.getCompletedAt()).isNotNull();
    }

    @Test
    void process_jobAlreadyFailed_doesNothing() {
        job.setStatus(VisionJobStatus.FAILED);
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));

        processor.process(JOB_ID, INPUT, "image/jpeg");

        verifyNoInteractions(visionProvider, mediaUploadService);
        verify(visionJobRepository, never()).save(any());
        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.FAILED);
    }

    @Test
    void process_optimisticLockFailureOnSave_isDroppedWithoutThrowing() {
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), any(), any(), anyDouble(), any())).thenReturn(OUTPUT);
        when(mediaUploadService.uploadImageBytes(any(), any(), anyString()))
                .thenReturn(uploaded("https://cdn.velora.test/out.png"));
        when(visionJobRepository.save(job))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(VisionJob.class, JOB_ID));

        assertThatCode(() -> processor.process(JOB_ID, INPUT, "image/jpeg")).doesNotThrowAnyException();
    }

    @Test
    void process_unknownJob_isNoOp() {
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> processor.process(JOB_ID, INPUT, "image/jpeg")).doesNotThrowAnyException();

        verifyNoInteractions(visionProvider, mediaUploadService);
        verify(visionJobRepository, never()).save(any());
    }

    // ---------- deterministic seed ----------

    private static final String FINGERPRINT = "deadbeef" + "0".repeat(56);

    private void stubSuccessfulProviderAndUpload() {
        lenient().when(visionProvider.transform(any(), any(), any(), any(), anyDouble(), any())).thenReturn(OUTPUT);
        lenient().when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/result")))
                .thenReturn(uploaded("https://cdn.velora.test/o.png"));
    }

    @Test
    void process_jobWithFingerprint_passesSeedDerivedFromFingerprint() {
        job.setRequestFingerprint(FINGERPRINT);
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        stubSuccessfulProviderAndUpload();

        processor.process(JOB_ID, INPUT, "image/jpeg");

        org.mockito.ArgumentCaptor<Long> seed = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(visionProvider).transform(eq(INPUT), eq("image/jpeg"), anyString(), any(), anyDouble(), seed.capture());
        assertThat(seed.getValue()).isEqualTo(VisionSeed.fromFingerprint(FINGERPRINT)).isEqualTo(3735928559L);
    }

    @Test
    void process_legacyJobWithoutFingerprint_passesNullSeed() {
        job.setRequestFingerprint(null);
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        stubSuccessfulProviderAndUpload();

        processor.process(JOB_ID, INPUT, "image/jpeg");

        verify(visionProvider).transform(eq(INPUT), eq("image/jpeg"), anyString(), any(), anyDouble(), isNull());
        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.COMPLETED);
    }

    @Test
    void process_sameJobInputsProduceSameSeedOnRepeatedCalls() {
        job.setRequestFingerprint(FINGERPRINT);
        VisionJob second = VisionJob.builder().id(JOB_ID).stylePrompt("scandinavian").status(VisionJobStatus.PROCESSING)
                .createdAt(Instant.now()).requestFingerprint(FINGERPRINT).build();
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job), Optional.of(second));
        stubSuccessfulProviderAndUpload();

        processor.process(JOB_ID, INPUT, "image/jpeg");
        processor.process(JOB_ID, INPUT, "image/jpeg");

        org.mockito.ArgumentCaptor<Long> seed = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(visionProvider, org.mockito.Mockito.times(2))
                .transform(any(), any(), any(), any(), anyDouble(), seed.capture());
        assertThat(seed.getAllValues()).hasSize(2).doesNotContainNull();
        assertThat(seed.getAllValues().get(0)).isEqualTo(seed.getAllValues().get(1));
    }

    // ---------- failure categories ----------

    private void assertFailsWith(Exception failure, String expectedError) {
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), any(), any(), anyDouble(), any()))
                .thenThrow((RuntimeException) failure);

        assertThatCode(() -> processor.process(JOB_ID, INPUT, "image/jpeg")).doesNotThrowAnyException();

        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.FAILED);
        assertThat(job.getError()).isEqualTo(expectedError);
        assertThat(job.getError()).doesNotContain("secret123").doesNotContain("provider body");
        verify(visionJobRepository).save(job);
    }

    @Test
    void process_moderationFailure_usesModerationMessage() {
        assertFailsWith(new VisionProviderException("api_key=secret123 provider body",
                        VisionFailureCategory.MODERATION, null),
                "We couldn't process this photo. Please try a different image.");
    }

    @Test
    void process_unavailableFailure_usesBusyMessage() {
        assertFailsWith(new VisionProviderException("api_key=secret123 provider body",
                        VisionFailureCategory.UNAVAILABLE, null),
                "The design service is busy right now. Please try again in a few minutes.");
    }

    @Test
    void process_genericProviderFailure_keepsExistingMessage() {
        assertFailsWith(new VisionProviderException("api_key=secret123 provider body",
                VisionFailureCategory.GENERIC, null), SAFE_ERROR);
    }

    @Test
    void process_providerExceptionWithoutCategory_defaultsToGenericMessage() {
        assertFailsWith(new VisionProviderException("api_key=secret123 provider body"), SAFE_ERROR);
    }

    @Test
    void process_nonProviderFailure_staysGenericEvenIfMessageMentionsModeration() {
        assertFailsWith(new IllegalStateException("content_moderation secret123 provider body"), SAFE_ERROR);
    }

    private String runStructuredAndCaptureNegative(String preferencesJson, com.velora.backend.dto.vision.Preservation expected) {
        job.setBuiltPrompt("BUILT PROMPT");
        job.setPreferences(preferencesJson);
        when(promptBuilder.negativePrompt(expected)).thenReturn("NEG-" + expected);
        when(visionJobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(visionProvider.transform(any(), any(), anyString(), any(), anyDouble(), any())).thenReturn(OUTPUT);
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/result")))
                .thenReturn(uploaded("https://cdn.velora.test/o.png"));

        processor.process(JOB_ID, INPUT, "image/jpeg");
        return "NEG-" + expected;
    }

    @Test
    void process_structuredJob_usesNegativePromptForStoredCreativePreservation() {
        String neg = runStructuredAndCaptureNegative(
                "{\"roomType\":\"BEDROOM\",\"preservation\":\"CREATIVE\"}", com.velora.backend.dto.vision.Preservation.CREATIVE);
        verify(visionProvider).transform(eq(INPUT), eq("image/jpeg"), eq("BUILT PROMPT"), eq(neg), anyDouble(), any());
    }

    @Test
    void process_structuredJob_usesNegativePromptForStoredPreserveOriginal() {
        String neg = runStructuredAndCaptureNegative(
                "{\"preservation\":\"PRESERVE_ORIGINAL\"}", com.velora.backend.dto.vision.Preservation.PRESERVE_ORIGINAL);
        verify(visionProvider).transform(eq(INPUT), eq("image/jpeg"), eq("BUILT PROMPT"), eq(neg), anyDouble(), any());
    }

    @Test
    void process_structuredJob_missingOrMalformedPreferencesFallBackToBalanced() {
        String neg = runStructuredAndCaptureNegative("not json", com.velora.backend.dto.vision.Preservation.BALANCED);
        verify(visionProvider).transform(eq(INPUT), eq("image/jpeg"), eq("BUILT PROMPT"), eq(neg), anyDouble(), any());
    }

    @Test
    void process_structuredJob_nullPreferencesFallBackToBalancedWithoutException() {
        String neg = runStructuredAndCaptureNegative(null, com.velora.backend.dto.vision.Preservation.BALANCED);
        verify(visionProvider).transform(eq(INPUT), eq("image/jpeg"), eq("BUILT PROMPT"), eq(neg), anyDouble(), any());
        assertThat(job.getStatus()).isEqualTo(VisionJobStatus.COMPLETED);
    }
}
