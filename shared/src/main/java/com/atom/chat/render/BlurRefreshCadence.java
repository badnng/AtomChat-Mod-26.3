package com.atom.chat.render;

/** Shared 60 Hz schedule for background sampling, independent of camera motion. */
public final class BlurRefreshCadence {
    public static final int FRAMES_PER_SECOND = 60;
    public static final long INTERVAL_NANOS = 1_000_000_000L / FRAMES_PER_SECOND;

    private BlurRefreshCadence() { }

    public static boolean captureDue(long now, long previous) {
        return previous == 0 || now - previous >= INTERVAL_NANOS;
    }

    public static long advanceCapture(long now, long previous) {
        // Preserve phase under normal frame jitter without catching up after a pause.
        return previous == 0 || now - previous >= INTERVAL_NANOS * 4
                ? now : previous + INTERVAL_NANOS;
    }
}
