package com.velora.backend.service.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.backend.config.VisionProperties;
import com.velora.backend.dto.media.MediaUploadResponse;
import com.velora.backend.dto.vision.BudgetTier;
import com.velora.backend.dto.vision.ColorPalette;
import com.velora.backend.dto.vision.DesignStyle;
import com.velora.backend.dto.vision.Preservation;
import com.velora.backend.dto.vision.RoomType;
import com.velora.backend.dto.vision.VisionFeature;
import com.velora.backend.dto.vision.VisionPreferences;
import com.velora.backend.dto.vision.VisionTransformAccepted;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.entity.vision.VisionJob;
import com.velora.backend.entity.vision.VisionJobStatus;
import com.velora.backend.exception.MediaUploadException;
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
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Phase 1 flow: validate -> read -> preprocess -> upload PREPROCESSED bytes -> save -> process. */
@ExtendWith(MockitoExtension.class)
class VisionServicePreprocessingTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final byte[] ORIGINAL = "original-with-exif-gps".getBytes();
    private static final byte[] PROCESSED = "clean-processed".getBytes();

    @Mock private VisionJobRepository visionJobRepository;
    @Mock private UserRepository userRepository;
    @Mock private MediaUploadService mediaUploadService;
    @Mock private VisionJobProcessor visionJobProcessor;
    @Mock private ImagePreprocessor imagePreprocessor;

    private VisionProperties properties;
    private VisionService visionService;

    private final MockMultipartFile image = new MockMultipartFile("image", "room.png", "image/png", ORIGINAL);

    @BeforeEach
    void setUp() {
        properties = new VisionProperties();
        properties.setDailyLimit(5);
        visionService = new VisionService(visionJobRepository, userRepository, mediaUploadService,
                visionJobProcessor, properties, new VisionPromptBuilder(properties), new ObjectMapper(),
                imagePreprocessor);
    }

    private static VisionPreferences prefs() {
        return new VisionPreferences(RoomType.LIVING_ROOM, DesignStyle.MODERN_LUXURY, ColorPalette.BEIGE_WALNUT, null,
                BudgetTier.MID_RANGE, List.of(VisionFeature.TV_UNIT), Preservation.BALANCED, null);
    }

    private void stubHappy() {
        lenient().when(imagePreprocessor.preprocess(any()))
                .thenReturn(new PreprocessedImage(PROCESSED, "image/jpeg", 800, 600));
        lenient().when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(0L);
        lenient().when(userRepository.getReferenceById(anyLong())).thenReturn(
                User.builder().id(1L).email("customer@velora.test").fullName("Cust").role(Role.CUSTOMER).build());
        lenient().when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/input")))
                .thenReturn(new MediaUploadResponse("https://cdn.velora.test/in.jpg", "pid", "jpg", 10L, 1, 1));
        lenient().when(visionJobRepository.save(any(VisionJob.class))).thenAnswer(inv -> {
            VisionJob j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(JOB_ID);
            }
            return j;
        });
    }

    private void assertNoSideEffectsAfterPreprocessing() {
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
        verify(mediaUploadService, never()).uploadImage(any(), any());
        verify(visionJobRepository, never()).save(any());
        verifyNoInteractions(visionJobProcessor);
    }

    // ---------- processed bytes are what leaves the service ----------

    @Test
    void submitLegacy_uploadsAndProcessesPreprocessedBytesNotTheOriginal() {
        stubHappy();

        VisionTransformAccepted result = visionService.submit(1L, image, "modern oak");

        assertThat(result.status()).isEqualTo(VisionJobStatus.PROCESSING);
        verify(imagePreprocessor).preprocess(eq(ORIGINAL));
        verify(mediaUploadService).uploadImageBytes(eq(PROCESSED), eq("image/jpeg"), eq("vision/input"));
        verify(mediaUploadService, never()).uploadImage(any(), any());
        verify(visionJobProcessor).process(eq(JOB_ID), eq(PROCESSED), eq("image/jpeg"));
    }

    @Test
    void submitStructured_uploadsAndProcessesPreprocessedBytesNotTheOriginal() {
        stubHappy();

        visionService.submit(1L, image, null, prefs());

        verify(mediaUploadService).uploadImageBytes(eq(PROCESSED), eq("image/jpeg"), eq("vision/input"));
        verify(mediaUploadService, never()).uploadImage(any(), any());
        verify(visionJobProcessor).process(eq(JOB_ID), eq(PROCESSED), eq("image/jpeg"));
    }

    @Test
    void submit_originalBytesNeverReachUploadOrProcessor() {
        stubHappy();

        visionService.submit(1L, image, null, prefs());

        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        verify(mediaUploadService).uploadImageBytes(uploaded.capture(), any(), any());
        assertThat(uploaded.getValue()).isNotEqualTo(ORIGINAL);
        ArgumentCaptor<byte[]> processed = ArgumentCaptor.forClass(byte[].class);
        verify(visionJobProcessor).process(any(), processed.capture(), any());
        assertThat(processed.getValue()).isNotEqualTo(ORIGINAL);
    }

    @Test
    void submit_savedJobPointsAtTheUploadedPreprocessedImage() {
        stubHappy();

        visionService.submit(1L, image, null, prefs());

        ArgumentCaptor<VisionJob> saved = ArgumentCaptor.forClass(VisionJob.class);
        verify(visionJobRepository).save(saved.capture());
        assertThat(saved.getValue().getInputImageUrl()).isEqualTo("https://cdn.velora.test/in.jpg");
    }

    // ---------- ordering ----------

    @Test
    void submit_orderIsValidateThenReadBytesThenLookupThenLimitsThenPreprocessThenUploadThenSaveThenProcess()
            throws Exception {
        stubHappy();
        org.springframework.web.multipart.MultipartFile file = org.mockito.Mockito.mock(
                org.springframework.web.multipart.MultipartFile.class);
        lenient().when(file.getContentType()).thenReturn("image/png");
        lenient().when(file.getBytes()).thenReturn(ORIGINAL);

        visionService.submit(1L, file, null, prefs());

        InOrder order = inOrder(mediaUploadService, file, visionJobRepository, imagePreprocessor, visionJobProcessor);
        order.verify(mediaUploadService).validate(file);
        order.verify(file).getBytes();
        order.verify(visionJobRepository)
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        anyLong(), anyString(), anyCollection(), any());
        order.verify(visionJobRepository).countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any());
        order.verify(visionJobRepository).countByUserIdAndStatusAndCreatedAtAfter(anyLong(), any(), any());
        order.verify(imagePreprocessor).preprocess(any());
        order.verify(mediaUploadService).uploadImageBytes(any(), any(), eq("vision/input"));
        order.verify(visionJobRepository).save(any(VisionJob.class));
        order.verify(visionJobProcessor).process(any(), any(), any());
    }

    @Test
    void submit_duplicateReturnsBeforeAnyPreprocessing() {
        VisionJob existing = VisionJob.builder().id(JOB_ID).status(VisionJobStatus.COMPLETED).build();
        when(visionJobRepository
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(1L), anyString(), anyCollection(), any()))
                .thenReturn(Optional.of(existing));

        visionService.submit(1L, image, null, prefs());

        verifyNoInteractions(imagePreprocessor, visionJobProcessor);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
    }

    @Test
    void submit_dailyLimitIsCheckedBeforePreprocessing() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(5L);

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(com.velora.backend.exception.RateLimitExceededException.class);

        verifyNoInteractions(imagePreprocessor, visionJobProcessor);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
    }

    @Test
    void submit_inFlightCapIsCheckedBeforePreprocessing() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(0L);
        when(visionJobRepository.countByUserIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(VisionJobStatus.PROCESSING), any())).thenReturn(2L);

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(com.velora.backend.exception.RateLimitExceededException.class);

        verifyNoInteractions(imagePreprocessor, visionJobProcessor);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
    }

    // ---------- preprocessing rejection ----------

    @Test
    void submitLegacy_tooSmallImageIsRejectedWithNoUploadSaveOrProcessor() {
        stubHappy();
        when(imagePreprocessor.preprocess(any()))
                .thenThrow(new IllegalArgumentException("Image is too small - the shorter side must be at least 512 pixels."));

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Image is too small - the shorter side must be at least 512 pixels.");

        assertNoSideEffectsAfterPreprocessing();
    }

    @Test
    void submitStructured_corruptImageIsRejectedWithNoUploadSaveOrProcessor() {
        stubHappy();
        when(imagePreprocessor.preprocess(any()))
                .thenThrow(new IllegalArgumentException(ImagePreprocessor.UNREADABLE));

        assertThatThrownBy(() -> visionService.submit(1L, image, null, prefs()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNREADABLE);

        assertNoSideEffectsAfterPreprocessing();
    }

    // ---------- fingerprint stays tied to the ORIGINAL bytes ----------

    private String lookupFingerprint(MockMultipartFile img, PreprocessedImage preprocessed) {
        stubHappy();
        when(imagePreprocessor.preprocess(any())).thenReturn(preprocessed);
        visionService.submit(1L, img, null, prefs());
        ArgumentCaptor<String> fp = ArgumentCaptor.forClass(String.class);
        verify(visionJobRepository, org.mockito.Mockito.atLeastOnce())
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(1L), fp.capture(), anyCollection(), any());
        String value = fp.getAllValues().get(0);
        org.mockito.Mockito.clearInvocations(visionJobRepository, mediaUploadService, visionJobProcessor,
                imagePreprocessor);
        return value;
    }

    @Test
    void fingerprint_sameOriginalGivesSameFingerprintEvenIfPreprocessingOutputDiffers() {
        String a = lookupFingerprint(image, new PreprocessedImage("out-one".getBytes(), "image/jpeg", 800, 600));
        String b = lookupFingerprint(image, new PreprocessedImage("out-two".getBytes(), "image/jpeg", 640, 480));

        assertThat(a).matches("[0-9a-f]{64}").isEqualTo(b);
    }

    @Test
    void fingerprint_differentOriginalsDifferEvenIfPreprocessingOutputIsIdentical() {
        PreprocessedImage same = new PreprocessedImage(PROCESSED, "image/jpeg", 800, 600);
        MockMultipartFile other = new MockMultipartFile("image", "room.png", "image/png", "another-original".getBytes());

        String a = lookupFingerprint(image, same);
        String b = lookupFingerprint(other, same);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void fingerprint_savedJobCarriesTheOriginalBasedFingerprint() {
        String lookup = lookupFingerprint(image, new PreprocessedImage(PROCESSED, "image/jpeg", 800, 600));
        stubHappy();

        visionService.submit(1L, image, null, prefs());

        ArgumentCaptor<VisionJob> saved = ArgumentCaptor.forClass(VisionJob.class);
        verify(visionJobRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestFingerprint()).isEqualTo(lookup);
    }

    // ---------- input validation is delegated to MediaUploadService.validate ----------

    @Test
    void submit_unsupportedContentTypeIsRejectedBeforePreprocessUploadSaveOrProcess() {
        stubHappy();
        MockMultipartFile pdf = new MockMultipartFile("image", "doc.pdf", "application/pdf", "%PDF-1.4".getBytes());
        org.mockito.Mockito.doThrow(new MediaUploadException("Invalid file type 'application/pdf'"))
                .when(mediaUploadService).validate(pdf);

        assertThatThrownBy(() -> visionService.submit(1L, pdf, "modern"))
                .isInstanceOf(MediaUploadException.class)
                .hasMessageContaining("Invalid file type");

        verify(imagePreprocessor, never()).preprocess(any());
        assertNoSideEffectsAfterPreprocessing();
    }

    @Test
    void submit_emptyFileIsRejectedBeforePreprocessUploadSaveOrProcess() {
        stubHappy();
        MockMultipartFile empty = new MockMultipartFile("image", "room.jpg", "image/jpeg", new byte[0]);
        org.mockito.Mockito.doThrow(new MediaUploadException("Please select a valid non-empty file to upload"))
                .when(mediaUploadService).validate(empty);

        assertThatThrownBy(() -> visionService.submit(1L, empty, null, prefs()))
                .isInstanceOf(MediaUploadException.class);

        verify(imagePreprocessor, never()).preprocess(any());
        assertNoSideEffectsAfterPreprocessing();
    }

    @Test
    void submitLegacy_validateRunsBeforeReadingBytesOrPreprocessing() throws Exception {
        stubHappy();
        org.springframework.web.multipart.MultipartFile file = org.mockito.Mockito.mock(
                org.springframework.web.multipart.MultipartFile.class);
        lenient().when(file.getContentType()).thenReturn("image/png");
        lenient().when(file.getBytes()).thenReturn(ORIGINAL);

        visionService.submit(1L, file, "modern");

        InOrder order = inOrder(mediaUploadService, file, imagePreprocessor);
        order.verify(mediaUploadService).validate(file);
        order.verify(file).getBytes();
        order.verify(imagePreprocessor).preprocess(any());
    }

    @Test
    void submitStructured_validateRunsBeforeReadingBytesOrPreprocessing() throws Exception {
        stubHappy();
        org.springframework.web.multipart.MultipartFile file = org.mockito.Mockito.mock(
                org.springframework.web.multipart.MultipartFile.class);
        lenient().when(file.getContentType()).thenReturn("image/png");
        lenient().when(file.getBytes()).thenReturn(ORIGINAL);

        visionService.submit(1L, file, null, prefs());

        InOrder order = inOrder(mediaUploadService, file, imagePreprocessor);
        order.verify(mediaUploadService).validate(file);
        order.verify(file).getBytes();
        order.verify(imagePreprocessor).preprocess(any());
    }

    @Test
    void submit_invalidFileIsRejectedBeforeAnyLookupCountReadPreprocessUploadSaveOrProcess() throws Exception {
        org.springframework.web.multipart.MultipartFile file = org.mockito.Mockito.mock(
                org.springframework.web.multipart.MultipartFile.class);
        org.mockito.Mockito.doThrow(new MediaUploadException("Invalid file type"))
                .when(mediaUploadService).validate(file);

        assertThatThrownBy(() -> visionService.submit(1L, file, null, prefs()))
                .isInstanceOf(MediaUploadException.class);
        assertThatThrownBy(() -> visionService.submit(1L, file, "modern"))
                .isInstanceOf(MediaUploadException.class);

        verify(file, never()).getBytes();
        verifyNoInteractions(visionJobRepository, imagePreprocessor, visionJobProcessor, userRepository);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
    }

    @Test
    void submit_uploadFailureOfPreprocessedBytesPropagatesAndCreatesNoJob() {
        stubHappy();
        when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/input")))
                .thenThrow(new MediaUploadException("Failed to upload media to cloud storage"));

        assertThatThrownBy(() -> visionService.submit(1L, image, "modern"))
                .isInstanceOf(MediaUploadException.class);

        verify(visionJobRepository, never()).save(any());
        verifyNoInteractions(visionJobProcessor);
    }
}
