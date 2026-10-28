package com.velora.backend.controller.vision;

import com.velora.backend.controller.common.WebLayerTest;
import com.velora.backend.dto.vision.VisionJobResponse;
import com.velora.backend.dto.vision.VisionTransformAccepted;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.vision.VisionJobStatus;
import com.velora.backend.exception.MediaUploadException;
import com.velora.backend.exception.RateLimitExceededException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.service.vision.VisionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import com.velora.backend.dto.vision.BudgetTier;
import com.velora.backend.dto.vision.ColorPalette;
import com.velora.backend.dto.vision.DesignStyle;
import com.velora.backend.dto.vision.Preservation;
import com.velora.backend.dto.vision.RoomType;
import com.velora.backend.dto.vision.VisionFeature;
import com.velora.backend.dto.vision.VisionPreferences;
import org.mockito.ArgumentCaptor;

import static com.velora.backend.controller.common.WebLayerSupport.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VisionController.class)
@WebLayerTest
class VisionControllerTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String URL = "/api/vision/transform";

    @Autowired private MockMvc mockMvc;
    @MockBean private VisionService visionService;

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "room.jpg", "image/jpeg", "bytes".getBytes());
    }

    // ---------- POST security ----------

    @Test
    void submit_anonymousGets401() throws Exception {
        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "modern"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(visionService);
    }

    @Test
    void submit_professionalGets403() throws Exception {
        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "modern")
                        .with(as(2L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(visionService);
    }

    @Test
    void submit_adminGets403() throws Exception {
        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "modern")
                        .with(as(3L, Role.ADMIN)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(visionService);
    }

    // ---------- POST validation ----------

    @Test
    void submit_blankStylePromptGets400() throws Exception {
        // stylePrompt is optional at the controller; the service enforces it for legacy requests
        when(visionService.submit(eq(1L), any(), any()))
                .thenThrow(new IllegalArgumentException("stylePrompt is required"));
        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "   ")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_missingStylePromptGets400() throws Exception {
        when(visionService.submit(eq(1L), any(), any()))
                .thenThrow(new IllegalArgumentException("stylePrompt is required"));
        mockMvc.perform(multipart(URL).file(image()).with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_tooLongStylePromptGets400() throws Exception {
        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "a".repeat(301))
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(visionService);
    }

    @Test
    void submit_stylePromptAtMaxLengthIsAccepted() throws Exception {
        when(visionService.submit(eq(1L), any(), any()))
                .thenReturn(new VisionTransformAccepted(ID.toString(), VisionJobStatus.PROCESSING));

        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "a".repeat(300))
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted());
    }

    @Test
    void submit_missingImageIsClientError() throws Exception {
        mockMvc.perform(multipart(URL).param("stylePrompt", "modern").with(as(1L, Role.CUSTOMER)))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(visionService);
    }

    // ---------- POST behaviour ----------

    @Test
    void submit_customerGets202WithIdAndProcessing() throws Exception {
        when(visionService.submit(eq(1L), any(), eq("modern minimalist")))
                .thenReturn(new VisionTransformAccepted(ID.toString(), VisionJobStatus.PROCESSING));

        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "modern minimalist")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.length()").value(2));

        verify(visionService).submit(eq(1L), any(), eq("modern minimalist"));
    }

    @Test
    void submit_rateLimitedGets429() throws Exception {
        when(visionService.submit(eq(1L), any(), any()))
                .thenThrow(new RateLimitExceededException("Daily limit reached"));

        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "modern")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void submit_invalidImageFromServiceGets400() throws Exception {
        when(visionService.submit(eq(1L), any(), any()))
                .thenThrow(new MediaUploadException("Unsupported image type"));

        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "modern")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_executorRejectedGets503() throws Exception {
        when(visionService.submit(eq(1L), any(), any()))
                .thenThrow(new com.velora.backend.exception.VisionUnavailableException("busy"));

        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "modern")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isServiceUnavailable());
    }

    // ---------- structured (customisable) requests ----------

    private void stubStructured() {
        when(visionService.submit(eq(1L), any(), any(), any(VisionPreferences.class)))
                .thenReturn(new VisionTransformAccepted(ID.toString(), VisionJobStatus.PROCESSING));
    }

    private VisionPreferences capturePrefs() {
        ArgumentCaptor<VisionPreferences> c = ArgumentCaptor.forClass(VisionPreferences.class);
        verify(visionService).submit(eq(1L), any(), any(), c.capture());
        return c.getValue();
    }

    @Test
    void submit_legacyRequestCallsThreeArgAndNeverStructured() throws Exception {
        when(visionService.submit(eq(1L), any(), eq("modern")))
                .thenReturn(new VisionTransformAccepted(ID.toString(), VisionJobStatus.PROCESSING));

        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "modern").with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted());

        verify(visionService).submit(eq(1L), any(), eq("modern"));
        verify(visionService, never()).submit(anyLong(), any(), any(), any());
    }

    @Test
    void submit_frontendRequestWithoutFeaturesOrStylePromptGets202AndEmptyFeatures() throws Exception {
        stubStructured();

        mockMvc.perform(multipart(URL).file(image())
                        .param("roomType", "LIVING_ROOM").param("style", "MODERN_LUXURY")
                        .param("colorPalette", "BEIGE_WALNUT").param("budgetTier", "MID_RANGE")
                        .param("preservation", "BALANCED")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.length()").value(2));

        VisionPreferences p = capturePrefs();
        assertThat(p.roomType()).isEqualTo(RoomType.LIVING_ROOM);
        assertThat(p.style()).isEqualTo(DesignStyle.MODERN_LUXURY);
        assertThat(p.colorPalette()).isEqualTo(ColorPalette.BEIGE_WALNUT);
        assertThat(p.budgetTier()).isEqualTo(BudgetTier.MID_RANGE);
        assertThat(p.preservation()).isEqualTo(Preservation.BALANCED);
        assertThat(p.features()).isNotNull().isEmpty();
        verify(visionService, never()).submit(anyLong(), any(), any());
    }

    @Test
    void submit_structuredRequestPassesAllFieldsIncludingRepeatedFeatures() throws Exception {
        stubStructured();

        mockMvc.perform(multipart(URL).file(image())
                        .param("roomType", "BEDROOM").param("style", "JAPANDI")
                        .param("colorPalette", "SAGE_CREAM").param("budgetTier", "PREMIUM_LUXURY")
                        .param("features", "TV_UNIT").param("features", "CURTAINS")
                        .param("preservation", "CREATIVE")
                        .param("additionalInstructions", "add a reading nook")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted());

        VisionPreferences p = capturePrefs();
        assertThat(p.roomType()).isEqualTo(RoomType.BEDROOM);
        assertThat(p.style()).isEqualTo(DesignStyle.JAPANDI);
        assertThat(p.colorPalette()).isEqualTo(ColorPalette.SAGE_CREAM);
        assertThat(p.budgetTier()).isEqualTo(BudgetTier.PREMIUM_LUXURY);
        assertThat(p.features()).containsExactlyInAnyOrder(VisionFeature.TV_UNIT, VisionFeature.CURTAINS);
        assertThat(p.preservation()).isEqualTo(Preservation.CREATIVE);
        assertThat(p.additionalInstructions()).isEqualTo("add a reading nook");
        assertThat(p.customColorDescription()).isNull();
    }

    @Test
    void submit_customPaletteWithDescriptionIsPassedThrough() throws Exception {
        stubStructured();

        mockMvc.perform(multipart(URL).file(image())
                        .param("roomType", "KITCHEN").param("style", "MINIMALIST")
                        .param("colorPalette", "CUSTOM").param("customColorDescription", "teal and gold")
                        .param("preservation", "PRESERVE_ORIGINAL")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted());

        VisionPreferences p = capturePrefs();
        assertThat(p.colorPalette()).isEqualTo(ColorPalette.CUSTOM);
        assertThat(p.customColorDescription()).isEqualTo("teal and gold");
    }

    @Test
    void submit_partialStructuredRequestIsPassedToServiceWhichValidates() throws Exception {
        stubStructured();

        mockMvc.perform(multipart(URL).file(image()).param("roomType", "KITCHEN").with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted());

        VisionPreferences p = capturePrefs();
        assertThat(p.roomType()).isEqualTo(RoomType.KITCHEN);
        assertThat(p.style()).isNull();
        assertThat(p.preservation()).isNull();
    }

    @Test
    void submit_serviceValidationFailureOnStructuredRequestGets400() throws Exception {
        when(visionService.submit(eq(1L), any(), any(), any(VisionPreferences.class)))
                .thenThrow(new IllegalArgumentException("style is required"));

        mockMvc.perform(multipart(URL).file(image()).param("roomType", "KITCHEN").with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submit_structuredWithStylePromptPassesItToService() throws Exception {
        stubStructured();

        mockMvc.perform(multipart(URL).file(image()).param("roomType", "KITCHEN").param("stylePrompt", "cosy")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted());

        verify(visionService).submit(eq(1L), any(), eq("cosy"), any(VisionPreferences.class));
    }

    @Test
    void submit_invalidEnumValueGets400() throws Exception {
        mockMvc.perform(multipart(URL).file(image()).param("roomType", "GARAGE").param("style", "MODERN_LUXURY")
                        .param("preservation", "BALANCED").with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(visionService);
    }

    @Test
    void submit_invalidFeatureValueGets400() throws Exception {
        mockMvc.perform(multipart(URL).file(image()).param("roomType", "KITCHEN").param("features", "JACUZZI")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(visionService);
    }

    @Test
    void submit_overLongStylePromptWithStructuredFieldsStillGets400() throws Exception {
        mockMvc.perform(multipart(URL).file(image()).param("roomType", "KITCHEN")
                        .param("stylePrompt", "a".repeat(301)).with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(visionService);
    }

    @Test
    void submit_structuredAnonymousGets401AndProfessionalGets403() throws Exception {
        mockMvc.perform(multipart(URL).file(image()).param("roomType", "KITCHEN"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart(URL).file(image()).param("roomType", "KITCHEN").with(as(2L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(visionService);
    }

    // ---------- GET ----------

    @Test
    void get_anonymousGets401() throws Exception {
        mockMvc.perform(get(URL + "/" + ID)).andExpect(status().isUnauthorized());
    }

    @Test
    void get_professionalGets403() throws Exception {
        mockMvc.perform(get(URL + "/" + ID).with(as(2L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_adminGets403() throws Exception {
        mockMvc.perform(get(URL + "/" + ID).with(as(3L, Role.ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_processingShape() throws Exception {
        when(visionService.get(1L, ID))
                .thenReturn(new VisionJobResponse(ID.toString(), VisionJobStatus.PROCESSING, null, null));

        mockMvc.perform(get(URL + "/" + ID).with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.resultImageUrl").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void get_completedShape() throws Exception {
        when(visionService.get(1L, ID)).thenReturn(new VisionJobResponse(
                ID.toString(), VisionJobStatus.COMPLETED, "https://cdn.velora.test/out.png", null));

        mockMvc.perform(get(URL + "/" + ID).with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.resultImageUrl").value("https://cdn.velora.test/out.png"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void get_failedShape() throws Exception {
        when(visionService.get(1L, ID)).thenReturn(new VisionJobResponse(
                ID.toString(), VisionJobStatus.FAILED, null,
                "We couldn't generate your design right now. Please try again."));

        mockMvc.perform(get(URL + "/" + ID).with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value("We couldn't generate your design right now. Please try again."))
                .andExpect(jsonPath("$.resultImageUrl").doesNotExist());
    }

    @Test
    void get_notFoundGets404() throws Exception {
        when(visionService.get(1L, ID)).thenThrow(new ResourceNotFoundException("Vision job not found"));

        mockMvc.perform(get(URL + "/" + ID).with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_invalidUuidGets400() throws Exception {
        mockMvc.perform(get(URL + "/not-a-uuid").with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(visionService);
    }

    @Test
    void submit_invalidEnumValueReturnsApiErrorShape() throws Exception {
        mockMvc.perform(multipart(URL).file(image())
                        .param("roomType", "BOGUS").param("style", "MODERN_LUXURY")
                        .param("preservation", "BALANCED")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(visionService);
    }

    @Test
    void submit_emptyFeaturesParamMeansEmptyList() throws Exception {
        stubStructured();

        mockMvc.perform(multipart(URL).file(image())
                        .param("roomType", "LIVING_ROOM").param("style", "MODERN_LUXURY")
                        .param("preservation", "BALANCED").param("features", "")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted());

        assertThat(capturePrefs().features()).isNotNull().isEmpty();
    }

    @Test
    void submit_whitespaceOnlyOptionalStringsAreTreatedAsAbsent() throws Exception {
        when(visionService.submit(eq(1L), any(), eq("modern")))
                .thenReturn(new VisionTransformAccepted(ID.toString(), VisionJobStatus.PROCESSING));

        mockMvc.perform(multipart(URL).file(image()).param("stylePrompt", "modern")
                        .param("customColorDescription", "   ").param("additionalInstructions", " \t ")
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isAccepted());

        verify(visionService, never()).submit(anyLong(), any(), any(), any());
    }

    @Test
    void submit_elevenRepeatedFeaturesAreForwardedAndServiceRejectionGets400() throws Exception {
        when(visionService.submit(eq(1L), any(), any(), any(VisionPreferences.class)))
                .thenThrow(new IllegalArgumentException("At most 10 features are allowed"));
        var request = multipart(URL).file(image())
                .param("roomType", "LIVING_ROOM").param("style", "MODERN_LUXURY")
                .param("preservation", "BALANCED");
        for (int i = 0; i < 11; i++) {
            request.param("features", "TV_UNIT");
        }

        mockMvc.perform(request.with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertThat(capturePrefs().features()).hasSize(11);
    }
}
