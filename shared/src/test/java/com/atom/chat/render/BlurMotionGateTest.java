package com.atom.chat.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Stationary cameras must not suppress world animation behind the blur. */
class BlurMotionGateTest {
    private static final long START = 1_000_000_000L;

    @BeforeEach void reset() {
        BlurMotionGate.resetForTest();
    }

    @Test void stationaryCameraKeepsEverySixtyHzSlot() {
        assertTrue(BlurMotionGate.allowFullRefresh(START, true, true));
        for (int frame = 1; frame <= 120; frame++) {
            assertTrue(BlurMotionGate.allowFullRefresh(START + frame * 16_666_667L, true, false));
        }
    }

    @Test void highFrameRatesReuseTextureBetweenSixtyHzSlots() {
        assertTrue(BlurMotionGate.allowFullRefresh(START, true, true));
        assertFalse(BlurMotionGate.allowFullRefresh(START + 8_333_333L, true, false));
        assertTrue(BlurMotionGate.allowFullRefresh(START + 16_666_667L, true, false));
        assertFalse(BlurMotionGate.allowFullRefresh(START + 25_000_000L, true, false));
        assertTrue(BlurMotionGate.allowFullRefresh(START + 33_333_334L, true, false));
    }

    @Test void forceRefreshImmediatelyReplacesResizedTexture() {
        assertFalse(BlurMotionGate.allowFullRefresh(START, false, false));
        assertTrue(BlurMotionGate.allowFullRefresh(START, false, true));
        assertTrue(BlurMotionGate.allowFullRefresh(START + 1, false, true));
        assertFalse(BlurMotionGate.allowFullRefresh(START + 2, true, false));
    }
}
