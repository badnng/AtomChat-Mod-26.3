package com.atom.chat.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class NativeWorldPixelCopyTest {
    @Test void flipsRowsForcesOpaqueAndDetectsRgbChangesWithoutChangingSourcePosition() {
        ByteBuffer source = ByteBuffer.allocateDirect(16);
        source.put(new byte[]{1, 2, 3, 0, 4, 5, 6, 30, 7, 8, 9, 60, 10, 11, 12, 90});
        source.position(5);
        byte[] destination = new byte[16];
        byte[] scratch = new byte[12]; // Reused scratch may be wider than the current region.
        assertTrue(NativeWorldPixelCopy.copyTopDownOpaque(source, 2, 2, destination, scratch, false));
        assertArrayEquals(new byte[]{7, 8, 9, -1, 10, 11, 12, -1, 1, 2, 3, -1, 4, 5, 6, -1}, destination);
        assertEquals(5, source.position());
        assertFalse(NativeWorldPixelCopy.copyTopDownOpaque(source, 2, 2, destination, scratch, false));

        source.put(3, (byte) 100); // World alpha does not cause a visual generation change.
        assertFalse(NativeWorldPixelCopy.copyTopDownOpaque(source, 2, 2, destination, scratch, false));
        source.put(0, (byte) 42);
        source.put(8, (byte) 43);
        assertTrue(NativeWorldPixelCopy.copyTopDownOpaque(source, 2, 2, destination, scratch, false));
        assertEquals(43, destination[0]);
        assertEquals(42, destination[8], "Rows must still copy after an earlier row first detects a change");
        assertTrue(NativeWorldPixelCopy.copyTopDownOpaque(source, 2, 2, destination, scratch, true),
                "A changed region origin must remain changed even if RGB pixels match");
    }
}
