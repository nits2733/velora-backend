package com.velora.backend.service.vision;

import com.velora.backend.config.VisionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Images are generated in-test; no binary fixtures and no network. */
class ImagePreprocessorTest {

    private VisionProperties props;
    private ImagePreprocessor preprocessor;

    @BeforeEach
    void setUp() {
        props = new VisionProperties();
        preprocessor = new ImagePreprocessor(props);
    }

    // ---------- image builders ----------

    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    /** White canvas with a 100x100 red block in the top-left corner. */
    private static BufferedImage redTopLeft(int w, int h) {
        BufferedImage img = solid(w, h, Color.WHITE);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 100, 100);
        g.dispose();
        return img;
    }

    private static BufferedImage noise(int w, int h, long seed) {
        Random rnd = new Random(seed);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0x1000000));
            }
        }
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThat(ImageIO.write(img, format, out)).isTrue();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] jpeg(BufferedImage img) {
        return encode(img, "jpg");
    }

    private static byte[] png(BufferedImage img) {
        return encode(img, "png");
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(img).as("output must be decodable").isNotNull();
            return img;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Inserts a raw JPEG segment (marker + payload) right after the first (JFIF APP0) segment. */
    private static byte[] insertSegment(byte[] jpeg, int marker, byte[] payload) {
        assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8);
        int insertAt = 2;
        if ((jpeg[2] & 0xFF) == 0xFF && (jpeg[3] & 0xFF) == 0xE0) {
            insertAt = 2 + 2 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        }
        int len = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, insertAt);
        out.write(0xFF);
        out.write(marker);
        out.write(len >> 8);
        out.write(len & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(jpeg, insertAt, jpeg.length - insertAt);
        return out.toByteArray();
    }

    /** Minimal big-endian Exif APP1 payload with a single Orientation (0x0112) SHORT entry, plus trailing bytes. */
    private static byte[] exifPayload(int orientation, String trailer) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        byte[] exif = {'E', 'x', 'i', 'f', 0, 0};
        p.write(exif, 0, exif.length);
        byte[] tiff = {
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,   // header, IFD0 at offset 8
                0x00, 0x01,                                     // one entry
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, // tag 0x0112, SHORT, count 1
                0x00, (byte) orientation, 0x00, 0x00,           // value
                0x00, 0x00, 0x00, 0x00                          // no next IFD
        };
        p.write(tiff, 0, tiff.length);
        byte[] extra = ascii(trailer);
        p.write(extra, 0, extra.length);
        return p.toByteArray();
    }

    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        return insertSegment(jpeg, 0xE1, exifPayload(orientation, ""));
    }

    private static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    private static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    private static void assertReddish(int rgb) {
        assertThat(red(rgb)).as("red channel of %06x", rgb & 0xFFFFFF).isGreaterThan(190);
        assertThat(green(rgb)).isLessThan(90);
        assertThat(blue(rgb)).isLessThan(90);
    }

    private static void assertWhitish(int rgb) {
        assertThat(red(rgb)).as("pixel %06x", rgb & 0xFFFFFF).isGreaterThan(220);
        assertThat(green(rgb)).isGreaterThan(220);
        assertThat(blue(rgb)).isGreaterThan(220);
    }

    // ---------- (1) downscale / no upscale ----------

    @Test
    void preprocess_largeJpegIsDownscaledToTargetPixelsPreservingAspectRatio() {
        byte[] in = jpeg(solid(3000, 2000, Color.GRAY));

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat((long) out.width() * out.height()).isLessThanOrEqualTo((long) (props.getTargetPixels() * 1.02));
        assertThat((long) out.width() * out.height()).isGreaterThan((long) (props.getTargetPixels() * 0.9));
        assertThat(Math.abs(out.width() - out.height() * 1.5)).isLessThanOrEqualTo(1.5);
        BufferedImage decoded = decode(out.bytes());
        assertThat(decoded.getWidth()).isEqualTo(out.width());
        assertThat(decoded.getHeight()).isEqualTo(out.height());
    }

    @Test
    void preprocess_largePngIsDownscaledAndPortraitAspectPreserved() {
        byte[] in = png(solid(1500, 3000, Color.DARK_GRAY));

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat((long) out.width() * out.height()).isLessThanOrEqualTo((long) (props.getTargetPixels() * 1.02));
        assertThat(Math.abs(out.height() - out.width() * 2.0)).isLessThanOrEqualTo(2.0);
        assertThat(out.width()).isLessThan(out.height());
    }

    @Test
    void preprocess_downscaleRespectsConfiguredTargetPixels() {
        props.setTargetPixels(400_000);
        byte[] in = jpeg(solid(1600, 1200, Color.GRAY));

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat((long) out.width() * out.height()).isLessThanOrEqualTo((long) (400_000 * 1.02));
        assertThat(Math.abs(out.width() - out.height() * (4.0 / 3.0))).isLessThanOrEqualTo(1.5);
    }

    @Test
    void preprocess_imageUnderTargetPixelsKeepsItsDimensions() {
        PreprocessedImage out = preprocessor.preprocess(jpeg(solid(1200, 800, Color.GRAY)));

        assertThat(out.width()).isEqualTo(1200);
        assertThat(out.height()).isEqualTo(800);
        BufferedImage decoded = decode(out.bytes());
        assertThat(decoded.getWidth()).isEqualTo(1200);
        assertThat(decoded.getHeight()).isEqualTo(800);
    }

    @Test
    void preprocess_imageExactlyAtTargetPixelsIsNotResized() {
        PreprocessedImage out = preprocessor.preprocess(png(solid(1000, 1000, Color.GRAY)));

        assertThat(out.width()).isEqualTo(1000);
        assertThat(out.height()).isEqualTo(1000);
    }

    @Test
    void preprocess_neverUpscalesSmallButAcceptableImage() {
        PreprocessedImage out = preprocessor.preprocess(jpeg(solid(600, 520, Color.GRAY)));

        assertThat(out.width()).isEqualTo(600);
        assertThat(out.height()).isEqualTo(520);
    }

    // ---------- (2) minimum short side ----------

    @Test
    void preprocess_shortSideBelowMinimumIsRejectedWithUserSafeMessageMentioningMinimum() {
        byte[] in = jpeg(solid(900, 400, Color.GRAY));

        assertThatThrownBy(() -> preprocessor.preprocess(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Image is too small - the shorter side must be at least 512 pixels.");
    }

    @Test
    void preprocess_rejectsPortraitWhoseWidthIsTooSmall() {
        byte[] in = png(solid(400, 900, Color.GRAY));

        assertThatThrownBy(() -> preprocessor.preprocess(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Image is too small - the shorter side must be at least 512 pixels.");
    }

    @Test
    void preprocess_shortSide511IsRejectedAnd512IsAccepted() {
        assertThatThrownBy(() -> preprocessor.preprocess(png(solid(800, 511, Color.GRAY))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Image is too small - the shorter side must be at least 512 pixels.");

        PreprocessedImage ok = preprocessor.preprocess(png(solid(800, 512, Color.GRAY)));
        assertThat(ok.height()).isEqualTo(512);
        assertThat(ok.width()).isEqualTo(800);
    }

    @Test
    void preprocess_minimumShortSideComesFromProperties() {
        props.setMinShortSide(256);
        assertThatCode(() -> preprocessor.preprocess(png(solid(300, 260, Color.GRAY))))
                .doesNotThrowAnyException();

        props.setMinShortSide(1024);
        assertThatThrownBy(() -> preprocessor.preprocess(png(solid(2000, 1000, Color.GRAY))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Image is too small - the shorter side must be at least 1024 pixels.");
    }

    // ---------- (3) pixel-count guard ----------

    @Test
    void preprocess_pixelCountAboveMaxInputPixelsIsRejected() {
        props.setMaxInputPixels(1_000L);

        assertThatThrownBy(() -> preprocessor.preprocess(png(solid(600, 600, Color.GRAY))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.TOO_LARGE);
        assertThatThrownBy(() -> preprocessor.preprocess(jpeg(solid(600, 600, Color.GRAY))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.TOO_LARGE);
    }

    @Test
    void preprocess_pixelCountExactlyAtMaxInputPixelsIsAllowed() {
        props.setMaxInputPixels(600L * 600L);

        assertThatCode(() -> preprocessor.preprocess(png(solid(600, 600, Color.GRAY))))
                .doesNotThrowAnyException();
    }

    /**
     * PNG header claiming 20000x20000 (400M pixels) with no real pixel data. A decoder that tried to
     * allocate the raster would blow up (OutOfMemoryError / different exception); the guard must reject it
     * from the header dimensions with an IllegalArgumentException.
     */
    @Test
    void preprocess_decompressionBombHeaderIsRejectedBeforeDecoding() {
        byte[] bomb = pngHeaderClaiming(20_000, 20_000);

        assertThatThrownBy(() -> preprocessor.preprocess(bomb))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).isNotBlank());
    }

    private static byte[] pngHeaderClaiming(int w, int h) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
            ihdr.write(new byte[]{'I', 'H', 'D', 'R'});
            ihdr.write(intBytes(w));
            ihdr.write(intBytes(h));
            ihdr.write(new byte[]{8, 2, 0, 0, 0}); // 8-bit RGB, deflate, no filter, no interlace
            out.write(intBytes(13));
            byte[] chunk = ihdr.toByteArray();
            out.write(chunk);
            CRC32 crc = new CRC32();
            crc.update(chunk);
            out.write(intBytes((int) crc.getValue()));
            // start of an IDAT chunk with bogus content, then EOF
            out.write(intBytes(64));
            out.write(new byte[]{'I', 'D', 'A', 'T', 0x08, (byte) 0xD7, 0x00, 0x00});
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] intBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    // ---------- (4) corrupt input ----------

    @Test
    void preprocess_nonImageBytesAreRejected() {
        byte[] junk = "this is definitely not an image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> preprocessor.preprocess(junk))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNSUPPORTED);
        assertThatThrownBy(() -> preprocessor.preprocess(junk))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNSUPPORTED);
    }

    @Test
    void preprocess_emptyBytesAreRejected() {
        assertThatThrownBy(() -> preprocessor.preprocess(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNREADABLE);
    }

    @Test
    void preprocess_truncatedJpegIsRejected() {
        byte[] full = jpeg(noise(800, 600, 1));
        byte[] truncated = java.util.Arrays.copyOf(full, 200);

        assertThatThrownBy(() -> preprocessor.preprocess(truncated))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNREADABLE);
    }

    @Test
    void preprocess_jpegMagicFollowedByGarbageIsRejected() {
        byte[] bad = new byte[512];
        new Random(7).nextBytes(bad);
        bad[0] = (byte) 0xFF;
        bad[1] = (byte) 0xD8;
        bad[2] = (byte) 0xFF;

        assertThatThrownBy(() -> preprocessor.preprocess(bad))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNREADABLE);
    }

    // ---------- (5) output format ----------

    @Test
    void preprocess_outputIsDecodableRgbJpegWithMagicBytes() {
        PreprocessedImage out = preprocessor.preprocess(png(solid(800, 600, Color.BLUE)));

        assertThat(out.contentType()).isEqualTo("image/jpeg");
        assertThat(out.bytes()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(out.bytes()[1] & 0xFF).isEqualTo(0xD8);
        BufferedImage decoded = decode(out.bytes());
        assertThat(decoded.getColorModel().hasAlpha()).isFalse();
        assertThat(decoded.getRaster().getNumBands()).isEqualTo(3);
        assertThat(decoded.getWidth()).isEqualTo(800);
        assertThat(decoded.getHeight()).isEqualTo(600);
        assertThat(blue(decoded.getRGB(400, 300))).isGreaterThan(200);
    }

    @Test
    void preprocess_jpegInputIsAlsoReEncodedAsJpeg() {
        PreprocessedImage out = preprocessor.preprocess(jpeg(solid(800, 600, Color.GRAY)));

        assertThat(out.contentType()).isEqualTo("image/jpeg");
        assertThat(out.bytes()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(out.bytes()[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void preprocess_pngWithAlphaIsFlattenedOntoWhite() {
        BufferedImage argb = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fillRect(0, 0, 800, 600);
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.setColor(Color.RED);
        g.fillRect(500, 300, 200, 200);
        g.dispose();

        PreprocessedImage out = preprocessor.preprocess(png(argb));

        BufferedImage decoded = decode(out.bytes());
        assertThat(decoded.getColorModel().hasAlpha()).isFalse();
        assertWhitish(decoded.getRGB(50, 50));      // transparent area becomes white, not black
        assertReddish(decoded.getRGB(600, 400));    // opaque content preserved
    }

    @Test
    void preprocess_encodesAtConfiguredJpegQuality() {
        BufferedImage img = noise(800, 600, 99);
        byte[] png = png(img);

        props.setJpegQuality(0.95f);
        int high = preprocessor.preprocess(png).bytes().length;
        props.setJpegQuality(0.2f);
        int low = preprocessor.preprocess(png).bytes().length;

        assertThat(low).isLessThan(high);
    }


    // ---------- (6) metadata stripping ----------

    @Test
    void preprocess_commentSegmentIsStrippedFromOutput() {
        String marker = "SECRET-COMMENT-MARKER-12345";
        byte[] in = insertSegment(jpeg(solid(800, 600, Color.GRAY)), 0xFE, ascii(marker));
        assertThat(contains(in, ascii(marker))).as("fixture must carry the marker").isNotNegative();

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat(contains(out.bytes(), ascii(marker))).isNegative();
        decode(out.bytes());
    }

    @Test
    void preprocess_exifSegmentAndGpsLikeBytesAreStrippedFromOutput() {
        String marker = "GPS-LOCATION-MARKER-98765";
        byte[] in = insertSegment(jpeg(solid(800, 600, Color.GRAY)), 0xE1, exifPayload(1, marker));
        assertThat(contains(in, ascii(marker))).as("fixture must carry the marker").isNotNegative();

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat(contains(out.bytes(), ascii(marker))).isNegative();
        assertThat(contains(out.bytes(), ascii("Exif"))).isNegative();
        decode(out.bytes());
    }

    @Test
    void preprocess_orientationTagIsNotCarriedIntoOutputSoViewersDoNotRotateTwice() {
        byte[] in = withExifOrientation(jpeg(redTopLeft(800, 600)), 6);

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat(contains(out.bytes(), ascii("Exif"))).isNegative();
    }

    // ---------- (7) EXIF orientation ----------

    @Test
    void preprocess_orientation6_rotatesNinetyClockwiseAndSwapsDimensions() {
        byte[] in = withExifOrientation(jpeg(redTopLeft(800, 600)), 6);

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat(out.width()).isEqualTo(600);
        assertThat(out.height()).isEqualTo(800);
        BufferedImage decoded = decode(out.bytes());
        assertThat(decoded.getWidth()).isEqualTo(600);
        assertThat(decoded.getHeight()).isEqualTo(800);
        // 90 degrees clockwise: the original top-left corner ends up top-right.
        assertReddish(decoded.getRGB(600 - 1 - 30, 30));
        assertWhitish(decoded.getRGB(30, 30));
        assertWhitish(decoded.getRGB(600 - 1 - 30, 800 - 30));
    }

    @Test
    void preprocess_orientation1_isUnchanged() {
        byte[] in = withExifOrientation(jpeg(redTopLeft(800, 600)), 1);

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat(out.width()).isEqualTo(800);
        assertThat(out.height()).isEqualTo(600);
        BufferedImage decoded = decode(out.bytes());
        assertReddish(decoded.getRGB(30, 30));
        assertWhitish(decoded.getRGB(800 - 30, 30));
    }

    @Test
    void preprocess_missingOrientation_isUnchanged() {
        PreprocessedImage out = preprocessor.preprocess(jpeg(redTopLeft(800, 600)));

        assertThat(out.width()).isEqualTo(800);
        assertThat(out.height()).isEqualTo(600);
        assertReddish(decode(out.bytes()).getRGB(30, 30));
    }

    @Test
    void preprocess_orientation6OnLargeImageIsRotatedThenDownscaled() {
        byte[] in = withExifOrientation(jpeg(solid(3000, 2000, Color.GRAY)), 6);

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat(out.height()).isGreaterThan(out.width());
        assertThat((long) out.width() * out.height()).isLessThanOrEqualTo((long) (props.getTargetPixels() * 1.02));
        assertThat(Math.abs(out.height() - out.width() * 1.5)).isLessThanOrEqualTo(1.5);
    }

    // ---------- (8) format is decided by bytes, never by declared type ----------

    @Test
    void preprocess_realWebpBytesAreRejectedWithUnsupportedMessage() {
        byte[] webp = "RIFF\u0010\u0000\u0000\u0000WEBPVP8 not-really-decodable".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> preprocessor.preprocess(webp))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Only JPEG and PNG photos are supported.");
    }

    @Test
    void preprocess_jpegWithCommentIsProcessedAndCommentStripped() {
        String marker = "LABEL-BYPASS-MARKER-1";
        byte[] in = insertSegment(jpeg(solid(3000, 2000, Color.GRAY)), 0xFE, ascii(marker));

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat(out.contentType()).isEqualTo("image/jpeg");
        assertThat(contains(out.bytes(), ascii(marker))).isNegative();
        assertThat((long) out.width() * out.height()).isLessThanOrEqualTo((long) (props.getTargetPixels() * 1.02));
    }


    @Test
    void preprocess_limitsApplyToBothJpegAndPng() {
        assertThatThrownBy(() -> preprocessor.preprocess(jpeg(solid(900, 400, Color.GRAY))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Image is too small - the shorter side must be at least 512 pixels.");
        props.setMaxInputPixels(1_000L);
        assertThatThrownBy(() -> preprocessor.preprocess(png(solid(600, 600, Color.GRAY))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.TOO_LARGE);
    }

    @Test
    void preprocess_garbageIsRejectedAsUnsupported() {
        byte[] junk = "GARBAGE-NOT-AN-IMAGE-AT-ALL".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> preprocessor.preprocess(junk))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNSUPPORTED);
    }

    @Test
    void preprocess_validImageWithLoweredMaxInputPixelsIsRejectedAndRaisedAccepted() {
        byte[] in = jpeg(solid(800, 600, Color.GRAY));
        props.setMaxInputPixels(800L * 600L - 1);
        assertThatThrownBy(() -> preprocessor.preprocess(in))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.TOO_LARGE);

        props.setMaxInputPixels(800L * 600L);
        assertThatCode(() -> preprocessor.preprocess(in)).doesNotThrowAnyException();
    }

    // ---------- truncated scan data ----------

    @Test
    void preprocess_jpegTruncatedMidScanIsRejected() {
        byte[] full = jpeg(noise(800, 600, 5));
        byte[] cut = java.util.Arrays.copyOf(full, full.length / 2);

        assertThatThrownBy(() -> preprocessor.preprocess(cut))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNREADABLE);
    }

    // ---------- grayscale and palette PNGs ----------

    @Test
    void preprocess_grayscalePngProcessesToRgbJpeg() {
        BufferedImage gray = new BufferedImage(800, 600, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0, 0, 800, 600);
        g.dispose();

        PreprocessedImage out = preprocessor.preprocess(png(gray));

        BufferedImage decoded = decode(out.bytes());
        assertThat(decoded.getRaster().getNumBands()).isEqualTo(3);
        assertThat(red(decoded.getRGB(400, 300))).isBetween(170, 215);
    }

    @Test
    void preprocess_palettePngProcessesToRgbJpeg() {
        BufferedImage indexed = new BufferedImage(800, 600, BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D g = indexed.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 800, 600);
        g.dispose();

        PreprocessedImage out = preprocessor.preprocess(png(indexed));

        assertReddish(decode(out.bytes()).getRGB(400, 300));
    }

    // ---------- subsampled decode of large images ----------

    @Test
    void preprocess_veryLargeImageIsSubsampledAndStillLandsNearTarget() {
        PreprocessedImage out = preprocessor.preprocess(jpeg(solid(4000, 3000, Color.GRAY)));

        assertThat((long) out.width() * out.height()).isLessThanOrEqualTo((long) (props.getTargetPixels() * 1.02));
        assertThat((long) out.width() * out.height()).isGreaterThan((long) (props.getTargetPixels() * 0.9));
        assertThat(Math.abs(out.width() - out.height() * (4.0 / 3.0))).isLessThanOrEqualTo(1.5);
    }

    @Test
    void preprocess_subsampledLargeImageStillAppliesOrientation() {
        byte[] in = withExifOrientation(jpeg(solid(4000, 3000, Color.GRAY)), 8);

        PreprocessedImage out = preprocessor.preprocess(in);

        assertThat(out.height()).isGreaterThan(out.width());
    }

    // ---------- all eight EXIF orientations ----------

    /** 800x600 image: red top-left block, green top-right block, blue bottom-left block, rest white. */
    private static BufferedImage quadrants() {
        BufferedImage img = solid(800, 600, Color.WHITE);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 150, 150);
        g.setColor(Color.GREEN);
        g.fillRect(650, 0, 150, 150);
        g.setColor(Color.BLUE);
        g.fillRect(0, 450, 150, 150);
        g.dispose();
        return img;
    }

    private static boolean isColour(int rgb, int r, int g, int b) {
        return Math.abs(red(rgb) - r) < 90 && Math.abs(green(rgb) - g) < 90 && Math.abs(blue(rgb) - b) < 90;
    }

    private static int[] mapped(int orientation, int sx, int sy) {
        int w = 800;
        int h = 600;
        return switch (orientation) {
            case 1 -> new int[]{sx, sy};
            case 2 -> new int[]{w - 1 - sx, sy};
            case 3 -> new int[]{w - 1 - sx, h - 1 - sy};
            case 4 -> new int[]{sx, h - 1 - sy};
            case 5 -> new int[]{sy, sx};
            case 6 -> new int[]{h - 1 - sy, sx};
            case 7 -> new int[]{h - 1 - sy, w - 1 - sx};
            default -> new int[]{sy, w - 1 - sx};
        };
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void preprocess_allEightOrientationsMapPixelsCorrectly(int orientation) {
        byte[] in = withExifOrientation(jpeg(quadrants()), orientation);

        PreprocessedImage out = preprocessor.preprocess(in);

        boolean swapped = orientation >= 5;
        assertThat(out.width()).isEqualTo(swapped ? 600 : 800);
        assertThat(out.height()).isEqualTo(swapped ? 800 : 600);
        BufferedImage decoded = decode(out.bytes());
        int[] red = mapped(orientation, 40, 40);
        int[] green = mapped(orientation, 760, 40);
        int[] blue = mapped(orientation, 40, 560);
        int[] white = mapped(orientation, 760, 560);
        assertThat(isColour(decoded.getRGB(red[0], red[1]), 255, 0, 0)).as("red at %s", java.util.Arrays.toString(red)).isTrue();
        assertThat(isColour(decoded.getRGB(green[0], green[1]), 0, 255, 0)).as("green at %s", java.util.Arrays.toString(green)).isTrue();
        assertThat(isColour(decoded.getRGB(blue[0], blue[1]), 0, 0, 255)).as("blue at %s", java.util.Arrays.toString(blue)).isTrue();
        assertWhitish(decoded.getRGB(white[0], white[1]));
    }

    // ---------- malformed EXIF falls back to orientation 1 ----------

    private static byte[] jpegWithRawApp1(byte[] app1Body, int declaredLen) {
        byte[] base = jpeg(redTopLeft(800, 600));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(base, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(declaredLen >> 8);
        out.write(declaredLen & 0xFF);
        out.write(app1Body, 0, app1Body.length);
        out.write(base, 2, base.length - 2);
        return out.toByteArray();
    }

    private static byte[] tiff(boolean little, long ifdOffset, int count, int orientation) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        byte[] exif = {'E', 'x', 'i', 'f', 0, 0};
        t.write(exif, 0, exif.length);
        t.write(little ? 'I' : 'M');
        t.write(little ? 'I' : 'M');
        put16(t, little, 42);
        put32(t, little, ifdOffset);
        put16(t, little, count);
        put16(t, little, 0x0112);
        put16(t, little, 3);
        put32(t, little, 1);
        put16(t, little, orientation);
        put16(t, little, 0);
        return t.toByteArray();
    }

    private static void put16(ByteArrayOutputStream o, boolean little, int v) {
        if (little) {
            o.write(v & 0xFF);
            o.write((v >> 8) & 0xFF);
        } else {
            o.write((v >> 8) & 0xFF);
            o.write(v & 0xFF);
        }
    }

    private static void put32(ByteArrayOutputStream o, boolean little, long v) {
        if (little) {
            put16(o, true, (int) (v & 0xFFFF));
            put16(o, true, (int) ((v >> 16) & 0xFFFF));
        } else {
            put16(o, false, (int) ((v >> 16) & 0xFFFF));
            put16(o, false, (int) (v & 0xFFFF));
        }
    }

    @Test
    void exif_bothByteOrdersAreReadCorrectly() {
        byte[] be = tiff(false, 8, 1, 6);
        byte[] le = tiff(true, 8, 1, 6);

        assertThat(ExifOrientation.read(jpegWithRawApp1(be, be.length + 2))).isEqualTo(6);
        assertThat(ExifOrientation.read(jpegWithRawApp1(le, le.length + 2))).isEqualTo(6);
    }

    @Test
    void exif_maliciousApp1LengthFallsBackWithoutThrowing() {
        byte[] body = tiff(false, 8, 1, 6);

        assertThat(ExifOrientation.read(jpegWithRawApp1(body, 0xFFFF))).isEqualTo(1);
        assertThat(ExifOrientation.read(jpegWithRawApp1(body, 0))).isEqualTo(1);
        assertThat(ExifOrientation.read(jpegWithRawApp1(body, 3))).isEqualTo(1);
    }

    @Test
    void exif_outOfRangeIfdOffsetFallsBack() {
        byte[] body = tiff(false, 0xFFFFFFF0L, 1, 6);
        assertThat(ExifOrientation.read(jpegWithRawApp1(body, body.length + 2))).isEqualTo(1);
        byte[] low = tiff(true, 0xFFFFFFFFL, 1, 6);
        assertThat(ExifOrientation.read(jpegWithRawApp1(low, low.length + 2))).isEqualTo(1);
    }

    @Test
    void exif_hugeEntryCountDoesNotThrowAndStillFindsTheFirstEntry() {
        byte[] body = tiff(false, 8, 0xFFFF, 6);
        assertThat(ExifOrientation.read(jpegWithRawApp1(body, body.length + 2))).isEqualTo(6);
    }

    @Test
    void exif_hugeEntryCountWithoutOrientationEntryFallsBack() {
        byte[] body = tiff(false, 8, 0xFFFF, 6);
        body[16] = 0x7F;
        assertThat(ExifOrientation.read(jpegWithRawApp1(body, body.length + 2))).isEqualTo(1);
    }

    @Test
    void exif_outOfRangeOrientationValueIsIgnored() {
        byte[] nine = tiff(false, 8, 1, 9);
        byte[] zero = tiff(false, 8, 1, 0);
        assertThat(ExifOrientation.read(jpegWithRawApp1(nine, nine.length + 2))).isEqualTo(1);
        assertThat(ExifOrientation.read(jpegWithRawApp1(zero, zero.length + 2))).isEqualTo(1);
    }

    @Test
    void exif_nonJpegAndTinyInputsFallBack() {
        assertThat(ExifOrientation.read(new byte[0])).isEqualTo(1);
        assertThat(ExifOrientation.read(new byte[]{(byte) 0xFF})).isEqualTo(1);
        assertThat(ExifOrientation.read("not a jpeg".getBytes(StandardCharsets.UTF_8))).isEqualTo(1);
    }

    @Test
    void preprocess_malformedExifStillProcessesWithoutRotation() {
        byte[] body = tiff(false, 0xFFFFFFF0L, 1, 6);

        PreprocessedImage out = preprocessor.preprocess(jpegWithRawApp1(body, body.length + 2));

        assertThat(out.width()).isEqualTo(800);
        assertThat(out.height()).isEqualTo(600);
    }

    // ---------- concurrency limit ----------

    @Test
    void preprocess_permitIsReleasedAfterSuccessAndAfterFailure() {
        props.setPreprocessConcurrency(1);
        props.setPreprocessWait(java.time.Duration.ofMillis(50));
        ImagePreprocessor limited = new ImagePreprocessor(props);
        byte[] ok = jpeg(solid(800, 600, Color.GRAY));
        byte[] tooSmall = jpeg(solid(900, 400, Color.GRAY));

        for (int i = 0; i < 4; i++) {
            limited.preprocess(ok);
            assertThatThrownBy(() -> limited.preprocess(tooSmall))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void preprocess_concurrentCallsAllCompleteWithAFewPermits() throws Exception {
        props.setPreprocessConcurrency(2);
        props.setPreprocessWait(java.time.Duration.ofSeconds(30));
        ImagePreprocessor limited = new ImagePreprocessor(props);
        byte[] img = jpeg(solid(1600, 1200, Color.GRAY));
        int threads = 6;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<PreprocessedImage>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> limited.preprocess(img)));
        }
        for (var f : results) {
            assertThat(f.get(60, java.util.concurrent.TimeUnit.SECONDS).contentType()).isEqualTo("image/jpeg");
        }
        pool.shutdownNow();
    }

    @Test
    void preprocess_overloadIsUnavailableWhenAllPermitsAreHeld() throws Exception {
        props.setPreprocessConcurrency(1);
        props.setPreprocessWait(java.time.Duration.ofMillis(30));
        ImagePreprocessor limited = new ImagePreprocessor(props);
        java.lang.reflect.Field field = ImagePreprocessor.class.getDeclaredField("permits");
        field.setAccessible(true);
        java.util.concurrent.Semaphore permits = (java.util.concurrent.Semaphore) field.get(limited);
        permits.acquire();

        assertThatThrownBy(() -> limited.preprocess(jpeg(solid(800, 600, Color.GRAY))))
                .isInstanceOf(com.velora.backend.exception.VisionUnavailableException.class)
                .hasMessage(ImagePreprocessor.BUSY);

        permits.release();
        assertThatCode(() -> limited.preprocess(jpeg(solid(800, 600, Color.GRAY))))
                .doesNotThrowAnyException();
    }

    @Test
    void preprocess_cheapRejectionsDoNotNeedAPermit() throws Exception {
        props.setPreprocessConcurrency(1);
        props.setPreprocessWait(java.time.Duration.ofMillis(10));
        ImagePreprocessor limited = new ImagePreprocessor(props);
        java.lang.reflect.Field field = ImagePreprocessor.class.getDeclaredField("permits");
        field.setAccessible(true);
        ((java.util.concurrent.Semaphore) field.get(limited)).acquire();

        assertThatThrownBy(() -> limited.preprocess("junk".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNSUPPORTED);
    }

    // ---------- W5: OOM, interrupt, subsampling boundaries, warning logging ----------

    @Test
    void preprocess_outOfMemoryDuringDecodeMapsToTooLargeAndReleasesThePermit() {
        props.setPreprocessConcurrency(1);
        props.setPreprocessWait(java.time.Duration.ofMillis(50));
        java.util.concurrent.atomic.AtomicBoolean oom = new java.util.concurrent.atomic.AtomicBoolean(true);
        ImagePreprocessor limited = new ImagePreprocessor(props) {
            @Override
            BufferedImage decode(byte[] data) {
                if (oom.get()) {
                    throw new OutOfMemoryError("simulated");
                }
                return super.decode(data);
            }
        };
        byte[] ok = jpeg(solid(800, 600, Color.GRAY));

        assertThatThrownBy(() -> limited.preprocess(ok))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.TOO_LARGE);

        oom.set(false);
        assertThatCode(() -> limited.preprocess(ok)).doesNotThrowAnyException();
    }

    @Test
    void preprocess_interruptWhileWaitingForPermitIs503AndRestoresInterruptFlag() throws Exception {
        props.setPreprocessConcurrency(1);
        props.setPreprocessWait(java.time.Duration.ofSeconds(30));
        ImagePreprocessor limited = new ImagePreprocessor(props);
        java.lang.reflect.Field field = ImagePreprocessor.class.getDeclaredField("permits");
        field.setAccessible(true);
        java.util.concurrent.Semaphore permits = (java.util.concurrent.Semaphore) field.get(limited);
        permits.acquire();
        byte[] ok = jpeg(solid(800, 600, Color.GRAY));

        java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean flag = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            try {
                limited.preprocess(ok);
            } catch (Throwable e) {
                thrown.set(e);
            }
            flag.set(Thread.currentThread().isInterrupted());
        });
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join(5000);

        assertThat(t.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(com.velora.backend.exception.VisionUnavailableException.class)
                .hasMessage(ImagePreprocessor.BUSY);
        assertThat(flag.get()).as("interrupt flag restored").isTrue();
        permits.release();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "1415,1413", "2000,1000", "1415,1414", "2001,1999", "2000,1999", "2000,2000", "2001,2000"})
    void preprocess_subsamplingBoundariesLandWithinTolerance(int w, int h) {
        PreprocessedImage out = preprocessor.preprocess(jpeg(solid(w, h, Color.GRAY)));

        long pixels = (long) out.width() * out.height();
        if ((long) w * h <= props.getTargetPixels()) {
            assertThat(pixels).isEqualTo((long) w * h);
        } else {
            assertThat(Math.abs(pixels - props.getTargetPixels()) / (double) props.getTargetPixels())
                    .as("pixels %s for %sx%s", pixels, w, h).isLessThanOrEqualTo(0.001);
        }
        double aspectIn = (double) w / h;
        double aspectOut = (double) out.width() / out.height();
        assertThat(Math.abs(aspectOut - aspectIn) / aspectIn).isLessThanOrEqualTo(0.002);
    }

    @Test
    void preprocess_readerWarningRejectsAndLogsTheWarningTextWithoutImageData() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ImagePreprocessor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            byte[] full = jpeg(noise(800, 600, 5));
            byte[] cut = java.util.Arrays.copyOf(full, full.length / 2);

            assertThatThrownBy(() -> preprocessor.preprocess(cut))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(ImagePreprocessor.UNREADABLE);

            java.util.List<String> messages = appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anyMatch(m -> m.startsWith("Image reader warning ("));
            assertThat(messages).allMatch(m -> m.length() < 400);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
