package com.velora.backend.service.media;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.velora.backend.dto.media.MediaUploadResponse;
import com.velora.backend.exception.MediaUploadException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudinaryMediaServiceTest {

    @Mock
    private Cloudinary cloudinary;

    @Mock
    private Uploader uploader;

    private CloudinaryMediaService mediaService;

    @BeforeEach
    void setUp() {
        com.velora.backend.config.MediaUploadProperties properties = new com.velora.backend.config.MediaUploadProperties();
        mediaService = new CloudinaryMediaService(cloudinary, properties);
    }

    @Test
    void uploadImageSuccessfullyUploadsValidJpeg() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "living-room.jpg",
                "image/jpeg",
                "fake-jpeg-content".getBytes()
        );

        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of(
                "secure_url", "https://res.cloudinary.com/velora/image/upload/v123/living-room.jpg",
                "public_id", "velora/portfolio/living-room-123",
                "format", "jpg",
                "bytes", 1024L,
                "width", 1920,
                "height", 1080
        ));

        MediaUploadResponse response = mediaService.uploadImage(file, "portfolio");

        assertThat(response).isNotNull();
        assertThat(response.secureUrl()).isEqualTo("https://res.cloudinary.com/velora/image/upload/v123/living-room.jpg");
        assertThat(response.publicId()).isEqualTo("velora/portfolio/living-room-123");
        assertThat(response.format()).isEqualTo("jpg");
        assertThat(response.width()).isEqualTo(1920);
        assertThat(response.height()).isEqualTo(1080);
    }

    @Test
    void uploadImageRejectsEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> mediaService.uploadImage(emptyFile, "portfolio"))
                .isInstanceOf(MediaUploadException.class)
                .hasMessageContaining("Please select a valid non-empty file");
    }

    @Test
    void uploadImageRejectsDisallowedContentType() {
        MockMultipartFile pdfFile = new MockMultipartFile(
                "file",
                "document.pdf",
                "application/pdf",
                "%PDF-1.4 dummy".getBytes()
        );

        assertThatThrownBy(() -> mediaService.uploadImage(pdfFile, "documents"))
                .isInstanceOf(MediaUploadException.class)
                .hasMessageContaining("Invalid file type 'application/pdf'")
                .hasMessageContaining("Only JPEG, PNG, and WebP images are allowed");
    }

    @Test
    void uploadImageRejectsOversizedFile() {
        byte[] largeBytes = new byte[11 * 1024 * 1024]; // 11 MB
        MockMultipartFile largeFile = new MockMultipartFile(
                "file",
                "giant.png",
                "image/png",
                largeBytes
        );

        assertThatThrownBy(() -> mediaService.uploadImage(largeFile, "uploads"))
                .isInstanceOf(MediaUploadException.class)
                .hasMessageContaining("exceeds maximum permitted limit of 10MB");
    }

    @Test
    void uploadImageWrapsCloudinaryExceptionsSafely() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.webp",
                "image/webp",
                "webp-content".getBytes()
        );

        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenThrow(new RuntimeException("Cloudinary API unavailable"));

        assertThatThrownBy(() -> mediaService.uploadImage(file, "avatars"))
                .isInstanceOf(MediaUploadException.class)
                .hasMessageContaining("Failed to upload media to cloud storage");
    }
}
