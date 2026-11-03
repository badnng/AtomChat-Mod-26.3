package com.atom.chat.render;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Converts mapped, bottom-up world rows without per-pixel buffer accesses or frame allocations. */
final class NativeWorldPixelCopy {
    private NativeWorldPixelCopy() { }

    static boolean copyTopDownOpaque(ByteBuffer source, int width, int height,
                                     byte[] destination, byte[] rowScratch, boolean changed) {
        int stride = width * 4;
        for (int y = 0; y < height; y++) {
            source.get((height - y - 1) * stride, rowScratch, 0, stride);
            for (int alpha = 3; alpha < stride; alpha += 4) rowScratch[alpha] = (byte) 255;
            int offset = y * stride;
            if (!changed && Arrays.mismatch(rowScratch, 0, stride, destination, offset, offset + stride) >= 0) {
                changed = true;
            }
            System.arraycopy(rowScratch, 0, destination, offset, stride);
        }
        return changed;
    }
}
