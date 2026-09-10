package com.velora.backend.controller.media;

import com.velora.backend.controller.common.WebLayerTest;

import com.velora.backend.dto.media.MediaUploadResponse;
import com.velora.backend.entity.user.Role;
import com.velora.backend.exception.MediaUploadException;
import com.velora.backend.exception.RateLimitExceededException;
import com.velora.backend.service.media.MediaUploadService;
import com.velora.backend.service.common.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static com.velora.backend.controller.common.WebLayerSupport.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MediaUploadController.class)
@WebLayerTest
class MediaUploadControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MediaUploadService mediaUploadService;

    @MockBean
    private RateLimiterService rateLimiterService;

    @Test
    void authenticatedUserCanUploadImageSuccessfully() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.jpg",
                "image/jpeg",
                "image-bytes".getBytes()
        );

        when(mediaUploadService.uploadImage(any(), eq("portfolio")))
                .thenReturn(new MediaUploadResponse(
                        "https://res.cloudinary.com/velora/image/upload/sample.jpg",
                        "velora/portfolio/sample",
                        "jpg",
                        1024L,
                        800,
                        600
                ));

        mockMvc.perform(multipart("/api/media/upload")
                        .file(file)
                        .param("folder", "portfolio")
                        .with(as(1L, Role.PROFESSIONAL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secureUrl").value("https://res.cloudinary.com/velora/image/upload/sample.jpg"))
                .andExpect(jsonPath("$.publicId").value("velora/portfolio/sample"))
                .andExpect(jsonPath("$.format").value("jpg"))
                .andExpect(jsonPath("$.width").value(800))
                .andExpect(jsonPath("$.height").value(600));
    }

    @Test
    void unauthenticatedUserIsRejectedWith401() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.jpg",
                "image/jpeg",
                "image-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/media/upload").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(mediaUploadService);
    }

    @Test
    void rateLimitTrippingReturns429TooManyRequests() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.jpg",
                "image/jpeg",
                "image-bytes".getBytes()
        );

        doThrow(new RateLimitExceededException("Upload limit exceeded. Please wait before uploading more files."))
                .when(rateLimiterService).checkMediaUploadRateLimit(anyString());

        mockMvc.perform(multipart("/api/media/upload")
                        .file(file)
                        .with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("Upload limit exceeded. Please wait before uploading more files."));

        verifyNoInteractions(mediaUploadService);
    }

    @Test
    void invalidFileTypeReturns400BadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "malicious.exe",
                "application/x-msdownload",
                "binary-bytes".getBytes()
        );

        when(mediaUploadService.uploadImage(any(), anyString()))
                .thenThrow(new MediaUploadException("Invalid file type 'application/x-msdownload'. Only JPEG, PNG, and WebP images are allowed."));

        mockMvc.perform(multipart("/api/media/upload")
                        .file(file)
                        .with(as(1L, Role.PROFESSIONAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid file type 'application/x-msdownload'. Only JPEG, PNG, and WebP images are allowed."));
    }
}
