package com.velora.backend.controller;

import com.velora.backend.dto.upload.UploadResponse;
import com.velora.backend.entity.UploadPurpose;
import com.velora.backend.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
@RequiredArgsConstructor
@Tag(name = "Uploads", description = "Image upload for portfolio covers, avatars, and booking inspiration photos")
public class UploadController {

    private final FileStorageService fileStorageService;

    @PostMapping
    @Operation(summary = "Upload an image and receive a URL to reference elsewhere (any authenticated user)")
    public ResponseEntity<UploadResponse> upload(@RequestParam MultipartFile file,
                                                  @RequestParam UploadPurpose purpose) {
        String url = fileStorageService.store(file, subfolderFor(purpose));
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadResponse(url));
    }

    private String subfolderFor(UploadPurpose purpose) {
        return switch (purpose) {
            case PORTFOLIO_COVER -> "portfolio-covers";
            case AVATAR -> "avatars";
            case BOOKING_INSPIRATION -> "inspiration";
        };
    }
}
