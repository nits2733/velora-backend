package com.velora.backend.service.vision;

/** Minimal reader for the EXIF Orientation tag (0x0112) of a JPEG; returns 1 (normal) when absent or malformed. */
final class ExifOrientation {

    private ExifOrientation() {
    }

    static int read(byte[] jpeg) {
        try {
            return parse(jpeg);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static int parse(byte[] d) {
        if (d.length < 4 || u8(d, 0) != 0xFF || u8(d, 1) != 0xD8) {
            return 1;
        }
        int pos = 2;
        while (pos + 4 <= d.length) {
            if (u8(d, pos) != 0xFF) {
                return 1;
            }
            int marker = u8(d, pos + 1);
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return 1;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2;
                continue;
            }
            int len = (u8(d, pos + 2) << 8) | u8(d, pos + 3);
            if (len < 2) {
                return 1;
            }
            int segStart = pos + 4;
            int segEnd = pos + 2 + len;
            if (marker == 0xE1 && segEnd <= d.length && segEnd - segStart >= 14
                    && d[segStart] == 'E' && d[segStart + 1] == 'x' && d[segStart + 2] == 'i'
                    && d[segStart + 3] == 'f' && d[segStart + 4] == 0 && d[segStart + 5] == 0) {
                int o = readTiff(d, segStart + 6, segEnd);
                if (o != 0) {
                    return o;
                }
            }
            pos = segEnd;
        }
        return 1;
    }

    private static int readTiff(byte[] d, int tiff, int end) {
        boolean little;
        if (d[tiff] == 'I' && d[tiff + 1] == 'I') {
            little = true;
        } else if (d[tiff] == 'M' && d[tiff + 1] == 'M') {
            little = false;
        } else {
            return 0;
        }
        if (u16(d, tiff + 2, little) != 42) {
            return 0;
        }
        long ifd = tiff + u32(d, tiff + 4, little);
        if (ifd + 2 > end) {
            return 0;
        }
        int count = u16(d, (int) ifd, little);
        for (int i = 0; i < count; i++) {
            long entry = ifd + 2 + 12L * i;
            if (entry + 12 > end) {
                return 0;
            }
            if (u16(d, (int) entry, little) == 0x0112) {
                int type = u16(d, (int) entry + 2, little);
                long n = u32(d, (int) entry + 4, little);
                if (type != 3 || n != 1) {
                    return 0;
                }
                int value = u16(d, (int) entry + 8, little);
                return value >= 1 && value <= 8 ? value : 0;
            }
        }
        return 0;
    }

    private static int u8(byte[] d, int i) {
        return d[i] & 0xFF;
    }

    private static int u16(byte[] d, int i, boolean little) {
        return little ? u8(d, i) | (u8(d, i + 1) << 8) : (u8(d, i) << 8) | u8(d, i + 1);
    }

    private static long u32(byte[] d, int i, boolean little) {
        long a = u16(d, i, little);
        long b = u16(d, i + 2, little);
        return little ? a | (b << 16) : (a << 16) | b;
    }
}
