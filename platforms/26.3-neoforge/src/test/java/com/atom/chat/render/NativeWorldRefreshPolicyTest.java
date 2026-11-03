package com.atom.chat.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeWorldRefreshPolicyTest {
    @Test void bothNativeBackendsUseTheSharedSixtyHzSchedule() {
        long captured = 1_000_000_000L;
        assertTrue(NativeWorldRefreshPolicy.captureDue(captured, 0));
        assertFalse(NativeWorldRefreshPolicy.captureDue(captured + 16_666_665L, captured));
        assertTrue(NativeWorldRefreshPolicy.captureDue(captured + 16_666_666L, captured));
        assertEquals(BlurRefreshCadence.advanceCapture(captured + 17_000_000L, captured),
                NativeWorldRefreshPolicy.advanceCapture(captured + 17_000_000L, captured));
    }
}
