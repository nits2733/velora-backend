package com.velora.backend.service.vision;

import com.velora.backend.config.VisionProperties;
import com.velora.backend.exception.VisionUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Normalises customer photos before they are stored or sent to the provider: bounded size, upright
 * orientation, white-flattened alpha, and re-encoded as a fresh JPEG so EXIF/GPS/comments/ICC are dropped.
 * The format is decided from the bytes, never from the client-declared content type. Decoding uses source
 * subsampling so a large photo is never fully materialised, and concurrency is capped for small instances.
 */
@Component
@Slf4j
public class ImagePreprocessor {

    static final String UNREADABLE = "The image could not be read.";
    static final String TOO_LARGE = "Image is too large.";
    static final String UNSUPPORTED = "Only JPEG and PNG photos are supported.";
    static final String BUSY = "The design service is busy right now. Please try again in a few minutes.";
    private static final String TOO_SMALL_PREFIX = "Image is too small";

    private final VisionProperties props;
    private final Semaphore permits;

    public ImagePreprocessor(VisionProperties props) {
        this.props = props;
        this.permits = new Semaphore(props.getPreprocessConcurrency());
    }

    public PreprocessedImage preprocess(byte[] original) {
        if (original == null || original.length == 0) {
            throw new IllegalArgumentException(UNREADABLE);
        }
        boolean jpeg = isJpeg(original);
        if (!jpeg && !isPng(original)) {
            throw new IllegalArgumentException(UNSUPPORTED);
        }

        acquire();
        try {
            return process(original, jpeg);
        } finally {
            permits.release();
        }
    }

    private void acquire() {
        try {
            if (!permits.tryAcquire(props.getPreprocessWait().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new VisionUnavailableException(BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VisionUnavailableException(BUSY);
        }
    }

    private PreprocessedImage process(byte[] original, boolean jpeg) {
        BufferedImage result;
        try {
            BufferedImage decoded = decode(original);
            int orientation = jpeg ? ExifOrientation.read(original) : 1;
            BufferedImage upright = flattenAndOrient(decoded, orientation);
            decoded.flush();

            long pixels = (long) upright.getWidth() * upright.getHeight();
            result = pixels > props.getTargetPixels()
                    ? downscale(upright, Math.sqrt((double) props.getTargetPixels() / pixels))
                    : upright;
        } catch (OutOfMemoryError e) {
            log.warn("Out of memory while preprocessing an image");
            throw new IllegalArgumentException(TOO_LARGE);
        }

        byte[] out = encodeJpeg(result);
        int w = result.getWidth();
        int h = result.getHeight();
        result.flush();
        return new PreprocessedImage(out, "image/jpeg", w, h);
    }

    private static boolean isJpeg(byte[] d) {
        return d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] d) {
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (d.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if (d[i] != sig[i]) {
                return false;
            }
        }
        return true;
    }

    /** Package-private so tests can substitute the decode step. */
    BufferedImage decode(byte[] data) {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException(UNREADABLE);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                long pixels = (long) w * h;
                if (pixels > props.getMaxInputPixels()) {
                    throw new IllegalArgumentException(TOO_LARGE);
                }
                if (Math.min(w, h) < props.getMinShortSide()) {
                    throw new IllegalArgumentException(TOO_SMALL_PREFIX + " - the shorter side must be at least "
                            + props.getMinShortSide() + " pixels.");
                }

                ImageReadParam param = reader.getDefaultReadParam();
                int factor = Math.max(1, (int) Math.floor(Math.sqrt((double) pixels / props.getTargetPixels())));
                if (factor > 1) {
                    param.setSourceSubsampling(factor, factor, 0, 0);
                }
                AtomicBoolean warned = new AtomicBoolean();
                String readerName = reader.getClass().getSimpleName();
                reader.addIIOReadWarningListener((source, warning) -> {
                    warned.set(true);
                    log.warn("Image reader warning ({}): {}", readerName, truncate(warning));
                });
                BufferedImage img = reader.read(0, param);
                if (img == null || warned.get()) {
                    throw new IllegalArgumentException(UNREADABLE);
                }
                return img;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalArgumentException iae && isUserMessage(iae)) {
                throw iae;
            }
            log.warn("Image could not be decoded: {}", e.getClass().getSimpleName());
            throw new IllegalArgumentException(UNREADABLE);
        }
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static boolean isUserMessage(IllegalArgumentException e) {
        String m = e.getMessage();
        return m != null && (m.equals(UNREADABLE) || m.equals(TOO_LARGE) || m.startsWith(TOO_SMALL_PREFIX));
    }

    private static BufferedImage flattenAndOrient(BufferedImage src, int orientation) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
            g.drawImage(src, transformFor(orientation, w, h), null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static AffineTransform transformFor(int orientation, int w, int h) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
    }

    private static BufferedImage downscale(BufferedImage src, double scale) {
        int targetW = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage current = src;
        while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
            BufferedImage next = resize(current, current.getWidth() / 2, current.getHeight() / 2,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            current.flush();
            current = next;
        }
        if (current.getWidth() != targetW || current.getHeight() != targetH) {
            BufferedImage next = resize(current, targetW, targetH, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            current.flush();
            current = next;
        }
        return current;
    }

    private static BufferedImage resize(BufferedImage src, int w, int h, Object interpolation) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private byte[] encodeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(props.getJpegQuality());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException(UNREADABLE);
        } finally {
            writer.dispose();
        }
    }
}
