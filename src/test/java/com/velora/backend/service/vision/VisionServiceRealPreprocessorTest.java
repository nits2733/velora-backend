package com.velora.backend.service.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.velora.backend.config.VisionProperties;
import com.velora.backend.dto.media.MediaUploadResponse;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.entity.vision.VisionJob;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.repository.vision.VisionJobRepository;
import com.velora.backend.service.media.MediaUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/** VisionService wired to the REAL ImagePreprocessor; only Cloudinary, repositories and the processor are mocked. */
@ExtendWith(MockitoExtension.class)
class VisionServiceRealPreprocessorTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String MARKER = "GPS-SECRET-MARKER-777";

    @Mock private VisionJobRepository visionJobRepository;
    @Mock private UserRepository userRepository;
    @Mock private MediaUploadService mediaUploadService;
    @Mock private VisionJobProcessor visionJobProcessor;

    private VisionService visionService;

    @BeforeEach
    void setUp() {
        VisionProperties properties = new VisionProperties();
        visionService = new VisionService(visionJobRepository, userRepository, mediaUploadService,
                visionJobProcessor, properties, new VisionPromptBuilder(properties), new ObjectMapper(),
                new ImagePreprocessor(properties));
        lenient().when(visionJobRepository.countByUserIdAndStatusInAndCreatedAtAfter(anyLong(), anyCollection(), any()))
                .thenReturn(0L);
        lenient().when(userRepository.getReferenceById(anyLong())).thenReturn(
                User.builder().id(1L).email("c@velora.test").fullName("C").role(Role.CUSTOMER).build());
        lenient().when(mediaUploadService.uploadImageBytes(any(), any(), eq("vision/input")))
                .thenReturn(new MediaUploadResponse("https://cdn.velora.test/in.jpg", "pid", "jpg", 10L, 1, 1));
        lenient().when(visionJobRepository.save(any(VisionJob.class))).thenAnswer(inv -> {
            VisionJob j = inv.getArgument(0);
            j.setId(JOB_ID);
            return j;
        });
    }

    private static byte[] largeJpegWithMetadata(Color colour) throws IOException {
        return largeJpegWithMetadata(colour, "");
    }

    private static byte[] largeJpegWithMetadata(Color colour, String extraComment) throws IOException {
        BufferedImage img = new BufferedImage(3000, 2000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, 3000, 2000);
        g.dispose();
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", plain);
        byte[] base = plain.toByteArray();

        byte[] exif = ("Exif\0\0" + MARKER).getBytes(StandardCharsets.ISO_8859_1);
        byte[] comment = ("COMMENT-" + MARKER + extraComment).getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(base, 0, 2);
        writeSegment(out, 0xE1, exif);
        writeSegment(out, 0xFE, comment);
        out.write(base, 2, base.length - 2);
        return out.toByteArray();
    }

    private static void writeSegment(ByteArrayOutputStream out, int marker, byte[] payload) {
        out.write(0xFF);
        out.write(marker);
        out.write((payload.length + 2) >> 8);
        out.write((payload.length + 2) & 0xFF);
        out.write(payload, 0, payload.length);
    }

    private static boolean containsMarker(byte[] data) {
        String s = new String(data, StandardCharsets.ISO_8859_1);
        return s.contains(MARKER);
    }

    private String submitAndCaptureFingerprint(byte[] original) {
        visionService.submit(1L, new MockMultipartFile("image", "room.jpg", "image/jpeg", original), "modern oak");
        ArgumentCaptor<String> fp = ArgumentCaptor.forClass(String.class);
        verify(visionJobRepository, atLeastOnce())
                .findFirstByUserIdAndRequestFingerprintAndStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
                        eq(1L), fp.capture(), anyCollection(), any());
        return fp.getAllValues().get(0);
    }

    @Test
    void submit_uploadsAndProcessesCleanDownscaledJpeg() throws IOException {
        byte[] original = largeJpegWithMetadata(Color.GRAY);
        assertThat(containsMarker(original)).isTrue();

        visionService.submit(1L, new MockMultipartFile("image", "room.jpg", "image/jpeg", original), "modern oak");

        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        verify(mediaUploadService).uploadImageBytes(uploaded.capture(), eq("image/jpeg"), eq("vision/input"));
        ArgumentCaptor<byte[]> processed = ArgumentCaptor.forClass(byte[].class);
        verify(visionJobProcessor).process(eq(JOB_ID), processed.capture(), eq("image/jpeg"));

        assertThat(uploaded.getValue()).isEqualTo(processed.getValue());
        byte[] clean = uploaded.getValue();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(clean));
        assertThat(decoded).isNotNull();
        assertThat((long) decoded.getWidth() * decoded.getHeight()).isLessThanOrEqualTo(1_020_000L);
        assertThat(decoded.getWidth()).isLessThan(3000);
        assertThat(containsMarker(clean)).isFalse();
        assertThat(new String(clean, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
        for (int i = 2; i + 3 < clean.length && i < 400; i++) {
            if ((clean[i] & 0xFF) == 0xFF) {
                int marker = clean[i + 1] & 0xFF;
                assertThat(marker).as("no EXIF (E1) or COM (FE) segment").isNotIn(0xE1, 0xFE);
            }
        }
    }

    @Test
    void submit_fingerprintDependsOnTheOriginalBytesNotThePreprocessedOutput() throws IOException {
        byte[] a = largeJpegWithMetadata(Color.GRAY);
        byte[] b = largeJpegWithMetadata(Color.GRAY, "-different-metadata-only");

        String fa = submitAndCaptureFingerprint(a);
        org.mockito.Mockito.clearInvocations(visionJobRepository);
        String fb = submitAndCaptureFingerprint(b);

        assertThat(fa).isNotEqualTo(fb);
        assertThat(fa).matches("[0-9a-f]{64}");
    }

    @Test
    void submit_sameOriginalBytesGiveTheSameFingerprint() throws IOException {
        byte[] a = largeJpegWithMetadata(Color.GRAY);

        String first = submitAndCaptureFingerprint(a);
        org.mockito.Mockito.clearInvocations(visionJobRepository);
        String second = submitAndCaptureFingerprint(a.clone());

        assertThat(first).isEqualTo(second);
    }

    @Test
    void submit_jpegBytesDeclaredAsWebpAreStillFullyProcessed() throws IOException {
        byte[] original = largeJpegWithMetadata(Color.GRAY);

        visionService.submit(1L, new MockMultipartFile("image", "room.webp", "image/webp", original), "modern oak");

        ArgumentCaptor<byte[]> uploaded = ArgumentCaptor.forClass(byte[].class);
        verify(mediaUploadService).uploadImageBytes(uploaded.capture(), eq("image/jpeg"), eq("vision/input"));
        assertThat(containsMarker(uploaded.getValue())).isFalse();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(uploaded.getValue()));
        assertThat((long) decoded.getWidth() * decoded.getHeight()).isLessThanOrEqualTo(1_020_000L);
    }
}
