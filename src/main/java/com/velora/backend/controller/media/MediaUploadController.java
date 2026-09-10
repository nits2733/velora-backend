package com.velora.backend.controller.media;

import com.velora.backend.dto.media.MediaUploadResponse;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.media.MediaUploadService;
import com.velora.backend.service.common.RateLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
@Tag(name = "Media", description = "Secure media asset upload and management")
@SecurityRequirement(name = "bearerAuth")
public class MediaUploadController {

    private final MediaUploadService mediaUploadService;
    private final RateLimiterService rateLimiterService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Upload an image (JPEG, PNG, WebP) to cloud storage (rate-limited)", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<MediaUploadResponse> upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", required = false, defaultValue = "uploads") String folder,
            HttpServletRequest request
    ) {
        String identifier = principal != null ? "user:" + principal.getId() : "ip:" + extractClientIp(request);
        rateLimiterService.checkMediaUploadRateLimit(identifier);

        MediaUploadResponse response = mediaUploadService.uploadImage(file, folder);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
