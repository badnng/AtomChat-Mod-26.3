package com.atom.chat.render;

/**
 * Classic panel blur schedule. Camera stillness must not hide moving world pixels.
 * All renderers now submit every available 60 Hz slot, reusing their last texture
 * only between slots or while an asynchronous capture is pending.
 */
public final class BlurMotionGate {
    private static long lastFullNanos;

    private BlurMotionGate() { }

    /** {@code nowNanos} uses the monotonic clock; first capture and resize bypass the schedule. */
    public static boolean allowFullRefresh(long nowNanos, boolean slotDue, boolean force) {
        if (force) {
            lastFullNanos = nowNanos;
            return true;
        }
        if (!slotDue || !BlurRefreshCadence.captureDue(nowNanos, lastFullNanos)) {
            return false;
        }
        lastFullNanos = BlurRefreshCadence.advanceCapture(nowNanos, lastFullNanos);
        return true;
    }

    /** Test-only state reset. */
    static void resetForTest() {
        lastFullNanos = 0;
    }
}
