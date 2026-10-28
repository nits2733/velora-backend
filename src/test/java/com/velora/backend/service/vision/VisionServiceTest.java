package com.velora.backend.service.vision;

import com.velora.backend.config.VisionProperties;
import com.velora.backend.dto.media.MediaUploadResponse;
import com.velora.backend.dto.vision.VisionJobResponse;
import com.velora.backend.dto.vision.VisionTransformAccepted;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.entity.vision.VisionJob;
import com.velora.backend.entity.vision.VisionJobStatus;
import com.velora.backend.exception.MediaUploadException;
import com.velora.backend.exception.RateLimitExceededException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.repository.vision.VisionJobRepository;
import com.velora.backend.service.media.MediaUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisionServiceTest {

    private static final String SAFE_ERROR = "We couldn't generate your design right now. Please try again.";
    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private VisionJobRepository visionJobRepository;
    @Mock private UserRepository userRepository;
    @Mock private MediaUploadService mediaUploadService;
    @Mock private VisionJobProcessor visionJobProcessor;
    @Mock private ImagePreprocessor imagePreprocessor;

    private static final byte[] PROCESSED = "processed-bytes".getBytes();

    private VisionProperties properties;
    private VisionService visionService;

    private final MockMultipartFile image =
            new MockMultipartFile("image", "room.jpg", "image/jpeg", "room-bytes".getBytes());

    @BeforeEach
    void setUp() {
        properties = new VisionProperties();
        properties.setDailyLimit(5);
        visionService = new VisionService(
                visionJobRepository, userRepository, mediaUploadService, visionJobProcessor, properties,
                new VisionPromptBuilder(properties), new com.fasterxml.jackson.databind.ObjectMapper(),
                imagePreprocessor);
        lenient().when(imagePreprocessor.preprocess(any()))
                .thenReturn(new PreprocessedImage(PROCESSED, "image/jpeg", 800, 600));
    }

    private User customer() {
        return User.builder().id(1L).email("customer@velora.test").fullName("Cust").role(Role.CUSTOMER).build();
    }

    private void stubHappyDependencies() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(0L);
        when(userRepository.getReferenceById(1L)).thenReturn(customer());
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/input")))
                .thenReturn(new MediaUploadResponse("https://cdn.velora.test/in.jpg", "pid", "jpg", 10L, 1, 1));
        when(visionJobRepository.save(any(VisionJob.class))).thenAnswer(inv -> {
            VisionJob j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(JOB_ID);
            }
            return j;
        });
    }

    @Test
    void submit_savesProcessingJobAndReturnsAccepted() {
        stubHappyDependencies();

        VisionTransformAccepted result = visionService.submit(1L, image, "modern minimalist");

        assertThat(result.id()).isEqualTo(JOB_ID.toString());
        assertThat(result.status()).isEqualTo(VisionJobStatus.PROCESSING);

        ArgumentCaptor<VisionJob> captor = ArgumentCaptor.forClass(VisionJob.class);
        verify(visionJobRepository).save(captor.capture());
        VisionJob saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(VisionJobStatus.PROCESSING);
        assertThat(saved.getStylePrompt()).isEqualTo("modern minimalist");
        assertThat(saved.getInputImageUrl()).isEqualTo("https://cdn.velora.test/in.jpg");
        assertThat(saved.getUser().getId()).isEqualTo(1L);
    }

    @Test
    void submit_handsWorkToProcessorOnlyAfterJobIsSaved() {
        stubHappyDependencies();

        visionService.submit(1L, image, "modern");

        InOrder order = inOrder(visionJobRepository, visionJobProcessor);
        order.verify(visionJobRepository).save(any(VisionJob.class));
        order.verify(visionJobProcessor).process(eq(JOB_ID), eq(PROCESSED), eq("image/jpeg"));
    }

    @Test
    void submit_checksLimitOverProcessingAndCompletedInLast24Hours() {
        stubHappyDependencies();
        Instant before = Instant.now();

        visionService.submit(1L, image, "modern");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<VisionJobStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(visionJobRepository, times(2)).countByUserIdAndStatusInAndCreatedAtAfter(eq(1L), statuses.capture(), since.capture());
        assertThat(Set.copyOf(statuses.getValue()))
                .containsExactlyInAnyOrder(VisionJobStatus.PROCESSING, VisionJobStatus.COMPLETED);
        assertThat(since.getValue()).isBetween(
                before.minus(Duration.ofHours(24)).minusSeconds(5),
                Instant.now().minus(Duration.ofHours(24)).plusSeconds(5));
    }

    @Test
    void submit_rejectsWhenDailyLimitReached() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(5L);

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(visionJobProcessor);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
        verify(visionJobRepository, never()).save(any());
    }

    @Test
    void submit_rejectsWhenCountExceedsLimit() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(9L);

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void submit_allowsWhenJustBelowLimit() {
        stubHappyDependencies();
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(4L);

        assertThat(visionService.submit(1L, image, "modern").status()).isEqualTo(VisionJobStatus.PROCESSING);
    }

    @Test
    void submit_recheckInsideLockRejectsWhenLimitReachedBetweenPreCheckAndSave() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(0L, 5L);
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/input")))
                .thenReturn(new MediaUploadResponse("https://cdn.velora.test/in.jpg", "pid", "jpg", 10L, 1, 1));

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(RateLimitExceededException.class);

        verify(visionJobRepository, never()).save(any());
        verifyNoInteractions(visionJobProcessor);
    }

    @Test
    void submit_rejectsWhenTwoJobsAlreadyInFlight() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(0L);
        when(visionJobRepository.countByUserIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(VisionJobStatus.PROCESSING), any())).thenReturn(2L);

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("already have designs being generated");

        verify(visionJobRepository, never()).save(any());
        verifyNoInteractions(visionJobProcessor);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
    }

    @Test
    void submit_oneInFlightJobIsAllowed_andInFlightWindowIsStaleAfter() {
        stubHappyDependencies();
        when(visionJobRepository.countByUserIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(VisionJobStatus.PROCESSING), any())).thenReturn(1L);
        Instant before = Instant.now();

        visionService.submit(1L, image, "modern");

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(visionJobRepository, org.mockito.Mockito.atLeastOnce()).countByUserIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(VisionJobStatus.PROCESSING), since.capture());
        assertThat(since.getValue()).isBetween(
                before.minus(properties.getStaleAfter()).minusSeconds(5),
                Instant.now().minus(properties.getStaleAfter()).plusSeconds(5));
        verify(visionJobRepository).save(any(VisionJob.class));
    }

    @Test
    void submit_trimsStylePromptBeforeStoring() {
        stubHappyDependencies();

        visionService.submit(1L, image, "  modern minimalist \n");

        ArgumentCaptor<VisionJob> captor = ArgumentCaptor.forClass(VisionJob.class);
        verify(visionJobRepository).save(captor.capture());
        assertThat(captor.getValue().getStylePrompt()).isEqualTo("modern minimalist");
    }

    @Test
    void submit_limitComesFromProperties() {
        properties.setDailyLimit(2);
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(2L);

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void submit_propagatesImageValidationFailureAndCreatesNoJob() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(0L);
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/input")))
                .thenThrow(new MediaUploadException("Unsupported image type"));

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(MediaUploadException.class)
                .hasMessage("Unsupported image type");

        verify(visionJobRepository, never()).save(any());
        verifyNoInteractions(visionJobProcessor);
    }

    @Test
    void submit_whenExecutorRejects_marksJobFailedAndThrowsVisionUnavailable() {
        stubHappyDependencies();
        doThrow(new TaskRejectedException("queue full secret-detail"))
                .when(visionJobProcessor).process(any(), any(), any());

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(com.velora.backend.exception.VisionUnavailableException.class)
                .hasMessageNotContaining("secret-detail");

        ArgumentCaptor<VisionJob> captor = ArgumentCaptor.forClass(VisionJob.class);
        verify(visionJobRepository, atLeast(1)).save(captor.capture());
        VisionJob last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(VisionJobStatus.FAILED);
        assertThat(last.getError()).isEqualTo(SAFE_ERROR);
    }

    @Test
    void get_returnsOwnersProcessingJob() {
        VisionJob job = VisionJob.builder().id(JOB_ID).user(customer()).status(VisionJobStatus.PROCESSING).build();
        when(visionJobRepository.findByIdAndUserId(JOB_ID, 1L)).thenReturn(Optional.of(job));

        VisionJobResponse r = visionService.get(1L, JOB_ID);

        assertThat(r.id()).isEqualTo(JOB_ID.toString());
        assertThat(r.status()).isEqualTo(VisionJobStatus.PROCESSING);
        assertThat(r.resultImageUrl()).isNull();
        assertThat(r.error()).isNull();
    }

    @Test
    void get_returnsCompletedJobWithResultUrl() {
        VisionJob job = VisionJob.builder().id(JOB_ID).user(customer()).status(VisionJobStatus.COMPLETED)
                .resultImageUrl("https://cdn.velora.test/out.png").build();
        when(visionJobRepository.findByIdAndUserId(JOB_ID, 1L)).thenReturn(Optional.of(job));

        VisionJobResponse r = visionService.get(1L, JOB_ID);

        assertThat(r.status()).isEqualTo(VisionJobStatus.COMPLETED);
        assertThat(r.resultImageUrl()).isEqualTo("https://cdn.velora.test/out.png");
        assertThat(r.error()).isNull();
    }

    @Test
    void get_returnsFailedJobWithError() {
        VisionJob job = VisionJob.builder().id(JOB_ID).user(customer()).status(VisionJobStatus.FAILED)
                .error(SAFE_ERROR).build();
        when(visionJobRepository.findByIdAndUserId(JOB_ID, 1L)).thenReturn(Optional.of(job));

        VisionJobResponse r = visionService.get(1L, JOB_ID);

        assertThat(r.status()).isEqualTo(VisionJobStatus.FAILED);
        assertThat(r.error()).isEqualTo(SAFE_ERROR);
        assertThat(r.resultImageUrl()).isNull();
    }

    @Test
    void get_missingOrForeignJobIsNotFoundNeverForbidden() {
        when(visionJobRepository.findByIdAndUserId(JOB_ID, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> visionService.get(2L, JOB_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(visionJobRepository, never()).findById(any());
    }
}
