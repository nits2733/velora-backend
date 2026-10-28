package com.velora.backend.service.vision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisionSeedTest {

    private static final String TAIL = "0".repeat(56);

    @Test
    void fromFingerprint_sameFingerprintGivesSameSeed() {
        String fp = "1a2b3c4d" + "e".repeat(56);
        assertThat(VisionSeed.fromFingerprint(fp)).isEqualTo(VisionSeed.fromFingerprint(fp));
    }

    @Test
    void fromFingerprint_onlyFirstEightHexCharsMatter() {
        assertThat(VisionSeed.fromFingerprint("1a2b3c4d" + "0".repeat(56)))
                .isEqualTo(VisionSeed.fromFingerprint("1a2b3c4d" + "f".repeat(56)));
    }

    @Test
    void fromFingerprint_differentFingerprintsGiveDifferentSeeds() {
        Set<Long> seeds = new HashSet<>();
        String[] prefixes = {"00000001", "00000002", "1a2b3c4d", "deadbeef", "cafebabe", "12345678", "9abcdef0"};
        for (String p : prefixes) {
            seeds.add(VisionSeed.fromFingerprint(p + TAIL));
        }
        assertThat(seeds).hasSameSizeAs(prefixes);
    }

    @Test
    void fromFingerprint_parsesFirstEightCharsAsUnsignedBigEndianHex() {
        assertThat(VisionSeed.fromFingerprint("0000000a" + TAIL)).isEqualTo(10L);
        assertThat(VisionSeed.fromFingerprint("00000100" + TAIL)).isEqualTo(256L);
        // 0xdeadbeef exceeds Integer.MAX_VALUE: must be treated as unsigned, not negative.
        assertThat(VisionSeed.fromFingerprint("deadbeef" + TAIL)).isEqualTo(3735928559L);
    }

    @Test
    void fromFingerprint_rangeBoundaries() {
        assertThat(VisionSeed.fromFingerprint("ffffffff" + TAIL)).isEqualTo(0L);
        assertThat(VisionSeed.fromFingerprint("00000000" + TAIL)).isEqualTo(0L);
        assertThat(VisionSeed.fromFingerprint("fffffffe" + TAIL)).isEqualTo(4294967294L);
        assertThat(VisionSeed.fromFingerprint("00000001" + TAIL)).isEqualTo(1L);
    }

    @Test
    void fromFingerprint_resultAlwaysWithinStabilitySeedRange() {
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 2000; i++) {
            String fp = String.format("%08x", rnd.nextInt()) + TAIL;
            assertThat(VisionSeed.fromFingerprint(fp)).isBetween(0L, 4294967294L);
        }
    }

    @Test
    void fromFingerprint_acceptsRealSha256HexLength() {
        assertThat(VisionSeed.fromFingerprint("a".repeat(64))).isBetween(0L, 4294967294L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "zzzzzzzz", "not-hex!", "1234567g", "abc", "-1234567", "+1234567", "0x123456"})
    void fromFingerprint_malformedInputThrows(String bad) {
        assertThatThrownBy(() -> VisionSeed.fromFingerprint(bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
