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
import com.velora.backend.exception.RateLimitExceededException;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

@ExtendWith(MockitoExtension.class)
class VisionServiceStructuredTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID EXISTING_ID = UUID.fromString("99999999-2222-3333-4444-555555555555");

    @Mock private VisionJobRepository visionJobRepository;
    @Mock private UserRepository userRepository;
    @Mock private MediaUploadService mediaUploadService;
    @Mock private VisionJobProcessor visionJobProcessor;
    @Mock private ImagePreprocessor imagePreprocessor;

    private static final byte[] PROCESSED = "processed-bytes".getBytes();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private VisionProperties properties;
    private VisionPromptBuilder promptBuilder;
    private VisionService visionService;

    private final MockMultipartFile image =
            new MockMultipartFile("image", "room.jpg", "image/jpeg", "room-bytes".getBytes());

    @BeforeEach
    void setUp() {
        properties = new VisionProperties();
        properties.setDailyLimit(5);
        promptBuilder = new VisionPromptBuilder(properties);
        visionService = new VisionService(visionJobRepository, userRepository, mediaUploadService,
                visionJobProcessor, properties, promptBuilder, objectMapper, imagePreprocessor);
        lenient().when(imagePreprocessor.preprocess(any()))
                .thenReturn(new PreprocessedImage(PROCESSED, "image/jpeg", 800, 600));
    }

    // ---------- helpers ----------

    private static VisionPreferences prefs() {
        return new VisionPreferences(RoomType.LIVING_ROOM, DesignStyle.MODERN_LUXURY, ColorPalette.BEIGE_WALNUT, null,
                BudgetTier.MID_RANGE, List.of(VisionFeature.TV_UNIT, VisionFeature.CURTAINS),
                Preservation.BALANCED, "more light please");
    }

    private static VisionPreferences with(RoomType room, DesignStyle style, ColorPalette palette, String custom,
                                          BudgetTier budget, List<VisionFeature> features, Preservation pres,
                                          String extra) {
        return new VisionPreferences(room, style, palette, custom, budget, features, pres, extra);
    }

    private User customer() {
        return User.builder().id(1L).email("customer@velora.test").fullName("Cust").role(Role.CUSTOMER).build();
    }

    private void stubHappyDependencies() {
        lenient().when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(0L);
        lenient().when(userRepository.getReferenceById(anyLong())).thenReturn(customer());
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

    private VisionJob savedJob() {
        ArgumentCaptor<VisionJob> captor = ArgumentCaptor.forClass(VisionJob.class);
        verify(visionJobRepository).save(captor.capture());
        return captor.getValue();
    }

    /** Runs submit and returns the fingerprint passed to the duplicate lookup. */
    private String fingerprintOf(Long userId, MockMultipartFile img, String stylePrompt, VisionPreferences p) {
        stubHappyDependencies();
        ArgumentCaptor<String> fp = ArgumentCaptor.forClass(String.class);
        visionService.submit(userId, img, stylePrompt, p);
        verify(visionJobRepository, org.mockito.Mockito.atLeastOnce())
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(userId), fp.capture(), anyCollection(), any());
        String value = fp.getAllValues().get(fp.getAllValues().size() - 1);
        org.mockito.Mockito.clearInvocations(visionJobRepository, mediaUploadService, visionJobProcessor);
        return value;
    }

    private void assertRejectedWithoutSideEffects(VisionPreferences p, String stylePrompt) {
        assertThatThrownBy(() -> visionService.submit(1L, image, stylePrompt, p))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mediaUploadService, visionJobProcessor);
        verify(visionJobRepository, never()).save(any());
    }

    // ---------- (a) legacy mode ----------

    @Test
    void submitLegacy_blankStylePromptIsRejected() {
        assertThatThrownBy(() -> visionService.submit(1L, image, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mediaUploadService, visionJobProcessor);
    }

    @Test
    void submitLegacy_nullStylePromptIsRejected() {
        assertThatThrownBy(() -> visionService.submit(1L, image, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mediaUploadService, visionJobProcessor);
    }

    @Test
    void submitLegacy_storesTrimmedPromptAndNoStructuredFields() {
        stubHappyDependencies();

        visionService.submit(1L, image, "  modern minimalist \n");

        VisionJob saved = savedJob();
        assertThat(saved.getStylePrompt()).isEqualTo("modern minimalist");
        assertThat(saved.getBuiltPrompt()).isNull();
        assertThat(saved.getPreferences()).isNull();
        assertThat(saved.getImageStrength()).isNull();
        assertThat(saved.getRequestFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void submitFourArg_nullPreferencesBehavesAsLegacy() {
        stubHappyDependencies();

        visionService.submit(1L, image, "modern", null);

        VisionJob saved = savedJob();
        assertThat(saved.getStylePrompt()).isEqualTo("modern");
        assertThat(saved.getBuiltPrompt()).isNull();
        assertThat(saved.getPreferences()).isNull();
        assertThat(saved.getImageStrength()).isNull();
    }

    @Test
    void submitFourArg_nullPreferencesAndBlankPromptIsRejected() {
        assertThatThrownBy(() -> visionService.submit(1L, image, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mediaUploadService, visionJobProcessor);
    }

    // ---------- (b) structured mode ----------

    @Test
    void submitStructured_storesPreferencesJsonBuiltPromptAndStrength() throws Exception {
        properties.setStrengthBalanced(0.55);
        stubHappyDependencies();
        VisionPreferences p = prefs();

        VisionTransformAccepted result = visionService.submit(1L, image, null, p);

        assertThat(result.id()).isEqualTo(JOB_ID.toString());
        assertThat(result.status()).isEqualTo(VisionJobStatus.PROCESSING);
        VisionJob saved = savedJob();
        assertThat(objectMapper.readValue(saved.getPreferences(), VisionPreferences.class)).isEqualTo(p);
        assertThat(saved.getBuiltPrompt()).isEqualTo(promptBuilder.build(p).prompt());
        assertThat(saved.getImageStrength()).isEqualTo(0.55);
        assertThat(saved.getStatus()).isEqualTo(VisionJobStatus.PROCESSING);
        assertThat(saved.getRequestFingerprint()).matches("[0-9a-f]{64}");
        verify(visionJobProcessor).process(eq(JOB_ID), any(), eq("image/jpeg"));
    }

    @Test
    void submitStructured_imageStrengthFollowsPreservation() {
        properties.setStrengthPreserveOriginal(0.21);
        properties.setStrengthCreative(0.81);
        stubHappyDependencies();

        VisionPreferences p = with(RoomType.BEDROOM, DesignStyle.JAPANDI, null, null, null, List.of(),
                Preservation.CREATIVE, null);
        visionService.submit(1L, image, null, p);

        assertThat(savedJob().getImageStrength()).isEqualTo(0.81);
    }

    @Test
    void submitStructured_stylePromptColumnIsShortSummary() {
        stubHappyDependencies();

        visionService.submit(1L, image, null, prefs());

        String summary = savedJob().getStylePrompt();
        assertThat(summary).isNotBlank().hasSizeLessThanOrEqualTo(300);
        assertThat(summary.toLowerCase()).contains("living room").contains("modern luxury");
    }

    @Test
    void submitStructured_summaryStaysWithin300EvenWithMaxInputs() {
        stubHappyDependencies();
        VisionPreferences p = with(RoomType.HOME_OFFICE, DesignStyle.CLASSIC_LUXURY, ColorPalette.CUSTOM,
                "c".repeat(60), BudgetTier.PREMIUM_LUXURY, Arrays.asList(VisionFeature.values()),
                Preservation.PRESERVE_ORIGINAL, "x".repeat(300));

        visionService.submit(1L, image, null, p);

        assertThat(savedJob().getStylePrompt()).hasSizeLessThanOrEqualTo(300);
    }

    @Test
    void submitStructured_suppliedStylePromptIsKeptTrimmedInColumn() {
        stubHappyDependencies();

        visionService.submit(1L, image, "  cozy reading corner  ", prefs());

        assertThat(savedJob().getStylePrompt()).isEqualTo("cozy reading corner");
    }

    @Test
    void submitStructured_nullFeaturesAndOptionalsAreAccepted() {
        stubHappyDependencies();
        VisionPreferences p = with(RoomType.OTHER, DesignStyle.BOHEMIAN, null, null, null, null,
                Preservation.PRESERVE_ORIGINAL, null);

        visionService.submit(1L, image, null, p);

        assertThat(savedJob().getBuiltPrompt()).isNotBlank();
    }

    @Test
    void submitStructured_customPaletteWithDescriptionIsAccepted() {
        stubHappyDependencies();
        VisionPreferences p = with(RoomType.BEDROOM, DesignStyle.CONTEMPORARY, ColorPalette.CUSTOM, "teal and gold",
                null, List.of(), Preservation.BALANCED, null);

        visionService.submit(1L, image, null, p);

        assertThat(savedJob().getBuiltPrompt()).contains("teal and gold");
    }

    @Test
    void submitStructured_missingRoomTypeIsRejected() {
        assertRejectedWithoutSideEffects(with(null, DesignStyle.MODERN_LUXURY, null, null, null, List.of(),
                Preservation.BALANCED, null), null);
    }

    @Test
    void submitStructured_missingStyleIsRejected() {
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, null, null, null, null, List.of(),
                Preservation.BALANCED, null), null);
    }

    @Test
    void submitStructured_missingPreservationIsRejected() {
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, null, null, null, List.of(),
                null, null), null);
    }

    @Test
    void submitStructured_customPaletteWithBlankDescriptionIsRejected() {
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, ColorPalette.CUSTOM, "   ",
                null, List.of(), Preservation.BALANCED, null), null);
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, ColorPalette.CUSTOM, null,
                null, List.of(), Preservation.BALANCED, null), null);
    }

    @Test
    void submitStructured_descriptionWithNonCustomPaletteIsRejected() {
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, ColorPalette.WHITE_OAK,
                "teal", null, List.of(), Preservation.BALANCED, null), null);
    }

    @Test
    void submitStructured_descriptionWithNoPaletteIsRejected() {
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, null,
                "teal", null, List.of(), Preservation.BALANCED, null), null);
    }

    @Test
    void submitStructured_descriptionOver60CharsIsRejected() {
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, ColorPalette.CUSTOM,
                "c".repeat(61), null, List.of(), Preservation.BALANCED, null), null);
    }

    @Test
    void submitStructured_descriptionOfExactly60CharsIsAccepted() {
        stubHappyDependencies();
        visionService.submit(1L, image, null, with(RoomType.KITCHEN, DesignStyle.MINIMALIST, ColorPalette.CUSTOM,
                "c".repeat(60), null, List.of(), Preservation.BALANCED, null));
        verify(visionJobRepository).save(any(VisionJob.class));
    }

    @Test
    void submitStructured_moreThanTenFeaturesIsRejected() {
        List<VisionFeature> eleven = new ArrayList<>(Arrays.asList(VisionFeature.values()));
        eleven.add(VisionFeature.TV_UNIT); // 11th value is a repeat
        assertThat(eleven).hasSize(11);
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, null, null, null, eleven,
                Preservation.BALANCED, null), null);
    }

    @Test
    void submitStructured_allTenDistinctFeaturesAreAccepted() {
        stubHappyDependencies();
        visionService.submit(1L, image, null, with(RoomType.KITCHEN, DesignStyle.MINIMALIST, null, null, null,
                Arrays.asList(VisionFeature.values()), Preservation.BALANCED, null));
        verify(visionJobRepository).save(any(VisionJob.class));
    }

    @Test
    void submitStructured_nullFeatureElementIsRejected() {
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, null, null, null,
                Arrays.asList(VisionFeature.TV_UNIT, null), Preservation.BALANCED, null), null);
    }

    @Test
    void submitStructured_additionalInstructionsOver300RawCharsIsRejected() {
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, null, null, null,
                List.of(), Preservation.BALANCED, "a".repeat(301)), null);
    }

    @Test
    void submitStructured_rawLengthIsCheckedBeforeSanitising() {
        // 301 raw chars, but collapses to well under 300 once whitespace is squeezed
        String padded = "a" + " ".repeat(300);
        assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, null, null, null,
                List.of(), Preservation.BALANCED, padded), null);
    }

    @Test
    void submitStructured_additionalInstructionsOfExactly300CharsIsAccepted() {
        stubHappyDependencies();
        visionService.submit(1L, image, null, with(RoomType.KITCHEN, DesignStyle.MINIMALIST, null, null, null,
                List.of(), Preservation.BALANCED, "a".repeat(300)));
        verify(visionJobRepository).save(any(VisionJob.class));
    }

    @Test
    void submitStructured_stylePromptOver300IsRejected() {
        assertRejectedWithoutSideEffects(prefs(), "s".repeat(301));
    }

    // ---------- (c) duplicate guard ----------

    @Test
    void duplicate_returnsExistingJobWithoutUploadSaveProcessorOrLimitCount() {
        VisionJob existing = VisionJob.builder().id(EXISTING_ID).user(customer())
                .status(VisionJobStatus.COMPLETED).build();
        when(visionJobRepository
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(1L), anyString(), anyCollection(), any()))
                .thenReturn(Optional.of(existing));

        VisionTransformAccepted result = visionService.submit(1L, image, null, prefs());

        assertThat(result.id()).isEqualTo(EXISTING_ID.toString());
        assertThat(result.status()).isEqualTo(VisionJobStatus.COMPLETED);
        verifyNoInteractions(visionJobProcessor, userRepository);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
        verify(visionJobRepository, never()).save(any());
        verify(visionJobRepository, never()).countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any());
        verify(visionJobRepository, never()).countByUserIdAndStatusAndCreatedAtAfter(anyLong(), any(), any());
    }

    @Test
    void duplicate_inProgressJobIsReturnedWithProcessingStatus() {
        VisionJob existing = VisionJob.builder().id(EXISTING_ID).user(customer())
                .status(VisionJobStatus.PROCESSING).build();
        when(visionJobRepository
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(1L), anyString(), anyCollection(), any()))
                .thenReturn(Optional.of(existing));

        VisionTransformAccepted result = visionService.submit(1L, image, "same thing");

        assertThat(result.id()).isEqualTo(EXISTING_ID.toString());
        assertThat(result.status()).isEqualTo(VisionJobStatus.PROCESSING);
        verifyNoInteractions(visionJobProcessor);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
    }

    @Test
    void duplicate_lookupUsesProcessingAndCompletedOnlyAndConfiguredWindow() {
        properties.setDuplicateWindow(Duration.ofSeconds(90));
        stubHappyDependencies();
        Instant before = Instant.now();

        visionService.submit(1L, image, null, prefs());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<VisionJobStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(visionJobRepository, org.mockito.Mockito.atLeastOnce())
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(1L), anyString(), statuses.capture(), since.capture());
        assertThat(Set.copyOf(statuses.getValue()))
                .containsExactlyInAnyOrder(VisionJobStatus.PROCESSING, VisionJobStatus.COMPLETED);
        assertThat(since.getValue()).isBetween(
                before.minus(Duration.ofSeconds(90)).minusSeconds(5),
                Instant.now().minus(Duration.ofSeconds(90)).plusSeconds(5));
    }

    @Test
    void duplicate_lookupHappensBeforeLimitCheckAndUpload() {
        stubHappyDependencies();

        visionService.submit(1L, image, null, prefs());

        InOrder order = inOrder(visionJobRepository, mediaUploadService);
        order.verify(visionJobRepository)
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        anyLong(), anyString(), anyCollection(), any());
        order.verify(visionJobRepository).countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any());
        order.verify(mediaUploadService).uploadImageBytes(any(), any(), eq("vision/input"));
    }

    @Test
    void duplicate_noMatchProceedsAndSavedJobCarriesLookupFingerprint() {
        stubHappyDependencies();

        visionService.submit(1L, image, null, prefs());

        ArgumentCaptor<String> fp = ArgumentCaptor.forClass(String.class);
        verify(visionJobRepository, org.mockito.Mockito.atLeastOnce())
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(1L), fp.capture(), anyCollection(), any());
        assertThat(savedJob().getRequestFingerprint()).isEqualTo(fp.getValue());
    }

    @Test
    void duplicate_changedPreferencesAreNotBlocked() {
        String original = fingerprintOf(1L, image, null, prefs());
        stubHappyDependencies();
        lenient().when(visionJobRepository
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        any(), any(), any(), any()))
                .thenAnswer(inv -> original.equals(inv.getArgument(1))
                        ? Optional.of(VisionJob.builder().id(EXISTING_ID).status(VisionJobStatus.COMPLETED).build())
                        : Optional.empty());

        VisionPreferences changed = with(RoomType.LIVING_ROOM, DesignStyle.MODERN_LUXURY, ColorPalette.BEIGE_WALNUT,
                null, BudgetTier.PREMIUM_LUXURY, List.of(VisionFeature.TV_UNIT, VisionFeature.CURTAINS),
                Preservation.BALANCED, "more light please");
        VisionTransformAccepted result = visionService.submit(1L, image, null, changed);

        assertThat(result.id()).isEqualTo(JOB_ID.toString());
        verify(mediaUploadService).uploadImageBytes(any(), any(), eq("vision/input"));
        verify(visionJobRepository).save(any(VisionJob.class));
    }

    @Test
    void duplicate_changedPreferencesStillSubjectToDailyLimit() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(5L);

        assertThatThrownBy(() -> visionService.submit(1L, image, null, prefs()))
                .isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(visionJobProcessor);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
        verify(visionJobRepository, never()).save(any());
    }

    @Test
    void duplicate_changedPreferencesStillSubjectToInFlightCap() {
        when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(0L);
        when(visionJobRepository.countByUserIdAndStatusAndCreatedAtAfter(
                eq(1L), eq(VisionJobStatus.PROCESSING), any())).thenReturn(2L);

        assertThatThrownBy(() -> visionService.submit(1L, image, null, prefs()))
                .isInstanceOf(RateLimitExceededException.class);

        verify(visionJobRepository, never()).save(any());
        verifyNoInteractions(visionJobProcessor);
        verify(mediaUploadService, never()).uploadImageBytes(any(), any(), any());
    }

    // ---------- fingerprint properties ----------

    @Test
    void fingerprint_isLowercaseHexSha256AndDeterministic() {
        String a = fingerprintOf(1L, image, null, prefs());
        String b = fingerprintOf(1L, image, null, prefs());
        assertThat(a).matches("[0-9a-f]{64}");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void fingerprint_changesWhenAnyPreferenceFieldChanges() {
        String base = fingerprintOf(1L, image, null, prefs());
        List<VisionPreferences> variants = List.of(
                with(RoomType.BEDROOM, DesignStyle.MODERN_LUXURY, ColorPalette.BEIGE_WALNUT, null, BudgetTier.MID_RANGE,
                        List.of(VisionFeature.TV_UNIT, VisionFeature.CURTAINS), Preservation.BALANCED, "more light please"),
                with(RoomType.LIVING_ROOM, DesignStyle.JAPANDI, ColorPalette.BEIGE_WALNUT, null, BudgetTier.MID_RANGE,
                        List.of(VisionFeature.TV_UNIT, VisionFeature.CURTAINS), Preservation.BALANCED, "more light please"),
                with(RoomType.LIVING_ROOM, DesignStyle.MODERN_LUXURY, ColorPalette.WHITE_OAK, null, BudgetTier.MID_RANGE,
                        List.of(VisionFeature.TV_UNIT, VisionFeature.CURTAINS), Preservation.BALANCED, "more light please"),
                with(RoomType.LIVING_ROOM, DesignStyle.MODERN_LUXURY, ColorPalette.CUSTOM, "teal", BudgetTier.MID_RANGE,
                        List.of(VisionFeature.TV_UNIT, VisionFeature.CURTAINS), Preservation.BALANCED, "more light please"),
                with(RoomType.LIVING_ROOM, DesignStyle.MODERN_LUXURY, ColorPalette.BEIGE_WALNUT, null, BudgetTier.BUDGET_FRIENDLY,
                        List.of(VisionFeature.TV_UNIT, VisionFeature.CURTAINS), Preservation.BALANCED, "more light please"),
                with(RoomType.LIVING_ROOM, DesignStyle.MODERN_LUXURY, ColorPalette.BEIGE_WALNUT, null, BudgetTier.MID_RANGE,
                        List.of(VisionFeature.TV_UNIT), Preservation.BALANCED, "more light please"),
                with(RoomType.LIVING_ROOM, DesignStyle.MODERN_LUXURY, ColorPalette.BEIGE_WALNUT, null, BudgetTier.MID_RANGE,
                        List.of(VisionFeature.TV_UNIT, VisionFeature.CURTAINS), Preservation.CREATIVE, "more light please"),
                with(RoomType.LIVING_ROOM, DesignStyle.MODERN_LUXURY, ColorPalette.BEIGE_WALNUT, null, BudgetTier.MID_RANGE,
                        List.of(VisionFeature.TV_UNIT, VisionFeature.CURTAINS), Preservation.BALANCED, "darker please"));
        List<String> seen = new ArrayList<>();
        seen.add(base);
        for (VisionPreferences v : variants) {
            String fp = fingerprintOf(1L, image, null, v);
            assertThat(fp).isNotIn(seen);
            seen.add(fp);
        }
    }

    @Test
    void fingerprint_changesWithImageBytesUserAndStylePrompt() {
        String base = fingerprintOf(1L, image, null, prefs());

        MockMultipartFile otherImage =
                new MockMultipartFile("image", "room.jpg", "image/jpeg", "different-bytes".getBytes());
        assertThat(fingerprintOf(1L, otherImage, null, prefs())).isNotEqualTo(base);
        assertThat(fingerprintOf(2L, image, null, prefs())).isNotEqualTo(base);
        assertThat(fingerprintOf(1L, image, "extra text", prefs())).isNotEqualTo(base);
    }

    @Test
    void fingerprint_stylePromptIsTrimmedBeforeHashing() {
        String a = fingerprintOf(1L, image, "extra text", prefs());
        String b = fingerprintOf(1L, image, "  extra text \n", prefs());
        assertThat(a).isEqualTo(b);
    }

    @Test
    void fingerprint_legacyDiffersFromStructured() {
        String legacy = fingerprintOf(1L, image, "modern", null);
        String structured = fingerprintOf(1L, image, "modern", prefs());
        assertThat(legacy).isNotEqualTo(structured);
    }

    // ---------- review follow-ups ----------

    @Test
    void duplicate_secondLookupInsideLockCatchesRaceAndSkipsSaveAndProcessor() {
        stubHappyDependencies();
        VisionJob raced = VisionJob.builder().id(EXISTING_ID).user(customer())
                .status(VisionJobStatus.PROCESSING).build();
        when(visionJobRepository
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(1L), anyString(), anyCollection(), any()))
                .thenReturn(Optional.empty(), Optional.of(raced));

        VisionTransformAccepted result = visionService.submit(1L, image, null, prefs());

        assertThat(result.id()).isEqualTo(EXISTING_ID.toString());
        assertThat(result.status()).isEqualTo(VisionJobStatus.PROCESSING);
        verify(visionJobRepository, never()).save(any());
        verifyNoInteractions(visionJobProcessor);
    }

    @Test
    void submitStructured_controlOnlyCustomColourDoesNotSatisfyCustomPalette() {
        for (String junk : new String[]{"\u0000", "​‮", " \u0007 "}) {
            assertRejectedWithoutSideEffects(with(RoomType.KITCHEN, DesignStyle.MINIMALIST, ColorPalette.CUSTOM, junk,
                    null, List.of(), Preservation.BALANCED, null), null);
        }
    }

    @Test
    void submitStructured_sanitisedToEmptyOptionalTextBecomesNullInStoredJson() throws Exception {
        stubHappyDependencies();
        visionService.submit(1L, image, null, with(RoomType.KITCHEN, DesignStyle.MINIMALIST, null, "\u0000​",
                null, List.of(), Preservation.BALANCED, "\u0007  ​"));
        VisionJob job = savedJob();
        com.fasterxml.jackson.databind.JsonNode json = objectMapper.readTree(job.getPreferences());
        assertThat(json.get("customColorDescription").isNull()).isTrue();
        assertThat(json.get("additionalInstructions").isNull()).isTrue();
        assertThat(job.getBuiltPrompt()).doesNotContain("Additional customer");
    }

    @Test
    void legacy_controlAndFormatCharsAreStrippedFromStoredPrompt() {
        stubHappyDependencies();
        visionService.submit(1L, image, "  modern\u0000 ​oak\n room ");
        assertThat(savedJob().getStylePrompt()).isEqualTo("modern oak room");
    }

    @Test
    void legacy_overLengthRawPromptIsRejectedOnThreeArgPath() {
        assertThatThrownBy(() -> visionService.submit(1L, image, "a".repeat(301)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mediaUploadService, visionJobProcessor);
    }

    @Test
    void legacy_controlOnlyPromptIsRejected() {
        assertThatThrownBy(() -> visionService.submit(1L, image, "\u0000​"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacy_neverInvokesPromptBuilder() {
        VisionPromptBuilder mockBuilder = org.mockito.Mockito.mock(VisionPromptBuilder.class);
        VisionService service = new VisionService(visionJobRepository, userRepository, mediaUploadService,
                visionJobProcessor, properties, mockBuilder, objectMapper, imagePreprocessor);
        stubHappyDependencies();

        service.submit(1L, image, "modern oak");

        verifyNoInteractions(mockBuilder);
        VisionJob job = savedJob();
        assertThat(job.getBuiltPrompt()).isNull();
        assertThat(job.getPreferences()).isNull();
        assertThat(job.getImageStrength()).isNull();
    }

    @Test
    void structured_taskRejectedMarksJobFailedAndThrowsVisionUnavailable() {
        stubHappyDependencies();
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("full"))
                .when(visionJobProcessor).process(any(), any(), any());

        assertThatThrownBy(() -> visionService.submit(1L, image, null, prefs()))
                .isInstanceOf(com.velora.backend.exception.VisionUnavailableException.class);

        ArgumentCaptor<VisionJob> captor = ArgumentCaptor.forClass(VisionJob.class);
        verify(visionJobRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        VisionJob failed = captor.getAllValues().get(1);
        assertThat(failed.getStatus()).isEqualTo(VisionJobStatus.FAILED);
        assertThat(failed.getError()).isEqualTo(VisionJobProcessor.FAILURE_MESSAGE);
        assertThat(failed.getBuiltPrompt()).isNotNull();
    }
}
