package com.atom.chat.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeWorldRegionTest {
    @Test void coversFractionalSpringBoundsAndConvertsTopDownCoordinates() {
        NativeWorldRegion region = NativeWorldRegion.covering(1920, 1080, 413.2f, 58.5f, 720.4f, 900.2f);
        assertEquals(new NativeWorldRegion(384, 0, 768, 960), region);
        assertEquals(120, region.sourceY(1080));
        assertTrue(region.x() <= 413.2f && region.x() + region.width() >= 1133.6f);
        assertEquals(768 * 960 * 4, region.byteCount());
    }

    @Test void clampsOddViewportsAndRejectsInvisibleOrInvalidBounds() {
        assertEquals(new NativeWorldRegion(0, 0, 1001, 777),
                NativeWorldRegion.covering(1001, 777, -25, -10, 1200, 900));
        assertNull(NativeWorldRegion.covering(1001, 777, 1100, 0, 200, 200));
        assertNull(NativeWorldRegion.covering(1001, 777, 0, 0, Float.NaN, 200));
        assertNull(NativeWorldRegion.covering(1001, 777, 0, 0, 0, 200));
    }

    @Test void smallMovementWithinAllocationBlockKeepsRegionStable() {
        assertEquals(NativeWorldRegion.covering(1920, 1080, 410, 100, 700, 800),
                NativeWorldRegion.covering(1920, 1080, 414, 103, 704, 802));
    }
}
