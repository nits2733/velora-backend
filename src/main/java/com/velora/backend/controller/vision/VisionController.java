package com.velora.backend.controller.vision;

import com.velora.backend.dto.vision.BudgetTier;
import com.velora.backend.dto.vision.ColorPalette;
import com.velora.backend.dto.vision.DesignStyle;
import com.velora.backend.dto.vision.Preservation;
import com.velora.backend.dto.vision.RoomType;
import com.velora.backend.dto.vision.VisionFeature;
import com.velora.backend.dto.vision.VisionJobResponse;
import com.velora.backend.dto.vision.VisionPreferences;
import com.velora.backend.dto.vision.VisionTransformAccepted;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.vision.VisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/vision/transform")
@RequiredArgsConstructor
@Tag(name = "Vision Studio", description = "AI room transformation (asynchronous jobs)")
@SecurityRequirement(name = "bearerAuth")
public class VisionController {

    private final VisionService visionService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Submit a room photo plus either structured design preferences (roomType, style, "
            + "colorPalette, customColorDescription, budgetTier, features, preservation, additionalInstructions) "
            + "or a legacy free-text stylePrompt; returns a job to poll (CUSTOMER). If any structured field is "
            + "present, roomType, style and preservation are required. See docs/vision-api-contract.md.")
    public ResponseEntity<VisionTransformAccepted> submit(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "stylePrompt", required = false) @Size(max = 300) String stylePrompt,
            @RequestParam(value = "roomType", required = false) RoomType roomType,
            @RequestParam(value = "style", required = false) DesignStyle style,
            @RequestParam(value = "colorPalette", required = false) ColorPalette colorPalette,
            @RequestParam(value = "customColorDescription", required = false) String customColorDescription,
            @RequestParam(value = "budgetTier", required = false) BudgetTier budgetTier,
            @RequestParam(value = "features", required = false) List<VisionFeature> features,
            @RequestParam(value = "preservation", required = false) Preservation preservation,
            @RequestParam(value = "additionalInstructions", required = false) String additionalInstructions) {
        String custom = blankToNull(customColorDescription);
        String extra = blankToNull(additionalInstructions);
        boolean structured = roomType != null || style != null || colorPalette != null || custom != null
                || budgetTier != null || (features != null && !features.isEmpty()) || preservation != null
                || extra != null;
        VisionTransformAccepted accepted = structured
                ? visionService.submit(principal.getId(), image, stylePrompt, new VisionPreferences(
                        roomType, style, colorPalette, custom, budgetTier,
                        features == null ? List.of() : features, preservation, extra))
                : visionService.submit(principal.getId(), image, stylePrompt);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Poll a transformation job owned by the caller (CUSTOMER)")
    public VisionJobResponse get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        return visionService.get(principal.getId(), id);
    }
}
