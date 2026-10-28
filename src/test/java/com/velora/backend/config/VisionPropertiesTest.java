package com.velora.backend.config;

import com.velora.backend.dto.vision.Preservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VisionPropertiesTest {

    @Test
    void defaults() {
        VisionProperties p = new VisionProperties();
        assertThat(p.isNegativePromptEnabled()).isTrue();
        assertThat(p.getDuplicateWindow()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.getStrengthPreserveOriginal()).isEqualTo(0.30);
        assertThat(p.getStrengthBalanced()).isEqualTo(0.50);
        assertThat(p.getStrengthCreative()).isEqualTo(0.70);
        assertThat(p.getImageStrength()).isEqualTo(0.35);
        assertThat(p.getDailyLimit()).isEqualTo(5);
    }

    @Test
    void strengthFor_mapsEachPreservationToItsConfiguredField() {
        VisionProperties p = new VisionProperties();
        p.setStrengthPreserveOriginal(0.11);
        p.setStrengthBalanced(0.22);
        p.setStrengthCreative(0.33);

        assertThat(p.strengthFor(Preservation.PRESERVE_ORIGINAL)).isEqualTo(0.11);
        assertThat(p.strengthFor(Preservation.BALANCED)).isEqualTo(0.22);
        assertThat(p.strengthFor(Preservation.CREATIVE)).isEqualTo(0.33);
    }

    @Test
    void strengthFor_defaultsAreOrdered() {
        VisionProperties p = new VisionProperties();
        assertThat(p.strengthFor(Preservation.PRESERVE_ORIGINAL)).isLessThan(p.strengthFor(Preservation.BALANCED));
        assertThat(p.strengthFor(Preservation.BALANCED)).isLessThan(p.strengthFor(Preservation.CREATIVE));
    }

    @Test
    void settersWork() {
        VisionProperties p = new VisionProperties();
        p.setNegativePromptEnabled(false);
        p.setDuplicateWindow(Duration.ofSeconds(5));
        assertThat(p.isNegativePromptEnabled()).isFalse();
        assertThat(p.getDuplicateWindow()).isEqualTo(Duration.ofSeconds(5));
    }

    private static java.util.Set<jakarta.validation.ConstraintViolation<VisionProperties>> validate(VisionProperties p) {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(p);
        }
    }

    @Test
    void validation_defaultsAreValid() {
        assertThat(validate(new VisionProperties())).isEmpty();
    }

    @Test
    void validation_rejectsStrengthOutOfRangeOrTooPrecise() {
        for (double bad : new double[]{-0.1, 1.01, 0.555}) {
            VisionProperties a = new VisionProperties();
            a.setStrengthBalanced(bad);
            assertThat(validate(a)).as("balanced %s", bad).isNotEmpty();
            VisionProperties b = new VisionProperties();
            b.setStrengthCreative(bad);
            assertThat(validate(b)).as("creative %s", bad).isNotEmpty();
            VisionProperties c = new VisionProperties();
            c.setStrengthPreserveOriginal(bad);
            assertThat(validate(c)).as("preserve %s", bad).isNotEmpty();
            VisionProperties d = new VisionProperties();
            d.setImageStrength(bad);
            assertThat(validate(d)).as("legacy %s", bad).isNotEmpty();
        }
    }

    @Test
    void validation_acceptsBoundaryValues() {
        VisionProperties p = new VisionProperties();
        p.setStrengthPreserveOriginal(0.0);
        p.setStrengthCreative(1.0);
        assertThat(validate(p)).isEmpty();
    }

    // ---------- image preprocessing settings ----------

    @Test
    void preprocessingDefaults() {
        VisionProperties p = new VisionProperties();
        assertThat(p.getMinShortSide()).isEqualTo(512);
        assertThat(p.getTargetPixels()).isEqualTo(1_000_000);
        assertThat(p.getMaxInputPixels()).isEqualTo(40_000_000L);
        assertThat(p.getJpegQuality()).isEqualTo(0.92f);
    }

    @Test
    void preprocessingSettersWork() {
        VisionProperties p = new VisionProperties();
        p.setMinShortSide(256);
        p.setTargetPixels(500_000);
        p.setMaxInputPixels(1_000L);
        p.setJpegQuality(0.8f);
        assertThat(p.getMinShortSide()).isEqualTo(256);
        assertThat(p.getTargetPixels()).isEqualTo(500_000);
        assertThat(p.getMaxInputPixels()).isEqualTo(1_000L);
        assertThat(p.getJpegQuality()).isEqualTo(0.8f);
        assertThat(validate(p)).isEmpty();
    }

    @Test
    void validation_rejectsNonPositiveMinShortSideTargetAndMaxPixels() {
        for (int bad : new int[]{0, -1}) {
            VisionProperties a = new VisionProperties();
            a.setMinShortSide(bad);
            assertThat(validate(a)).as("minShortSide %s", bad).isNotEmpty();
            VisionProperties b = new VisionProperties();
            b.setTargetPixels(bad);
            assertThat(validate(b)).as("targetPixels %s", bad).isNotEmpty();
            VisionProperties c = new VisionProperties();
            c.setMaxInputPixels(bad);
            assertThat(validate(c)).as("maxInputPixels %s", bad).isNotEmpty();
        }
    }

    @Test
    void validation_rejectsJpegQualityOutsideZeroExclusiveToOneInclusive() {
        for (float bad : new float[]{0f, -0.1f, 1.01f, 2f}) {
            VisionProperties p = new VisionProperties();
            p.setJpegQuality(bad);
            assertThat(validate(p)).as("jpegQuality %s", bad).isNotEmpty();
        }
    }

    @Test
    void validation_acceptsJpegQualityBoundaryOne() {
        VisionProperties p = new VisionProperties();
        p.setJpegQuality(1.0f);
        assertThat(validate(p)).isEmpty();
    }

    @Test
    void preprocessConcurrencyAndWaitDefaultsAndValidation() {
        VisionProperties p = new VisionProperties();
        assertThat(p.getPreprocessConcurrency()).isEqualTo(2);
        assertThat(p.getPreprocessWait()).isEqualTo(Duration.ofSeconds(5));
        p.setPreprocessConcurrency(0);
        assertThat(validate(p)).isNotEmpty();
        p.setPreprocessConcurrency(1);
        assertThat(validate(p)).isEmpty();
    }
}
