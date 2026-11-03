package com.atom.chat.render;

/** Both native backends use the same 60 Hz world-sampling schedule. */
final class NativeWorldRefreshPolicy {
    private NativeWorldRefreshPolicy() { }

    static boolean captureDue(long now, long previous) {
        return BlurRefreshCadence.captureDue(now, previous);
    }

    static long advanceCapture(long now, long previous) {
        return BlurRefreshCadence.advanceCapture(now, previous);
    }
}
