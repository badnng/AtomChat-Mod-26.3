package com.atom.chat.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlurRefreshCadenceTest {
    @Test void sixtyHzSlotsAreIndependentOfGameFrameRate() {
        long start = 1_000_000_000L;
        assertEquals(60, BlurRefreshCadence.FRAMES_PER_SECOND);
        assertTrue(BlurRefreshCadence.captureDue(start, 0));
        assertFalse(BlurRefreshCadence.captureDue(start + 16_666_665L, start));
        assertTrue(BlurRefreshCadence.captureDue(start + 16_666_666L, start));
        for (int gameFps : new int[] {30, 60, 120, 144, 240}) {
            long previous = start;
            int captures = 0;
            for (int frame = 1; frame <= gameFps * 2; frame++) {
                long now = start + frame * 1_000_000_000L / gameFps;
                if (BlurRefreshCadence.captureDue(now, previous)) {
                    previous = BlurRefreshCadence.advanceCapture(now, previous);
                    captures++;
                }
            }
            assertEquals(Math.min(gameFps, 60) * 2, captures, "game fps=" + gameFps);
        }
    }

    @Test void jitterDoesNotHalveRefreshAndPausesDoNotCauseBursts() {
        long start = 1_000_000_000L;
        long previous = start;
        int captures = 0;
        for (int frame = 1; frame <= 120; frame++) {
            long now = start + frame * 16_666_667L + (frame % 2 == 0 ? 100_000 : -100_000);
            if (BlurRefreshCadence.captureDue(now, previous)) {
                previous = BlurRefreshCadence.advanceCapture(now, previous);
                captures++;
            }
        }
        assertTrue(captures >= 118, "Frame jitter must not quantize 60 Hz into 30 Hz");
        long resumed = previous + 5_000_000_000L;
        assertEquals(resumed, BlurRefreshCadence.advanceCapture(resumed, previous));
        assertFalse(BlurRefreshCadence.captureDue(resumed + 1, resumed));
    }
}
