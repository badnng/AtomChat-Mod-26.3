package com.atom.chat.automation;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Locale;

/** Opt-in development observations; the watchdog never calls the game or graphics APIs. */
public final class AutomationRenderStallDiagnostics {
    private static final boolean ENABLED = Boolean.getBoolean("atomchat.automation")
            && Boolean.getBoolean("atomchat.automation.renderStallDiagnostics");
    private static final long STALL_NANOS = 1_000_000_000L;
    private static final long SUMMARY_NANOS = 10_000_000_000L;

    public enum Stage {
        IDLE(false), FRAME(true), GAME_RENDER(true), SUBMIT(true), PRESENT(true), FRAME_LIMITER(false),
        WINDOW_ACTIVATION(true);

        final boolean watch;

        Stage(boolean watch) { this.watch = watch; }
    }

    // One render-thread writer. An even, unchanged revision publishes a coherent snapshot.
    private static volatile long revision;
    private static Thread renderThread;
    private static Stage stage = Stage.IDLE;
    private static long stageStarted;
    private static long frame;
    private static final long[] counts = new long[Stage.values().length];
    private static final long[] totals = new long[counts.length];
    private static final long[] maxima = new long[counts.length];

    private AutomationRenderStallDiagnostics() { }

    public static void beginFrame() {
        if (!ENABLED) return;
        if (renderThread == null) {
            renderThread = Thread.currentThread();
            Thread watchdog = new Thread(AutomationRenderStallDiagnostics::watch,
                    "atomchat-render-stall-diagnostics");
            watchdog.setDaemon(true);
            watchdog.start();
        }
        transition(Stage.FRAME, true);
    }

    public static void enter(Stage next) {
        if (ENABLED) transition(next, false);
    }

    private static void transition(Stage next, boolean newFrame) {
        long now = System.nanoTime();
        revision++;
        if (stageStarted != 0 && stage != Stage.IDLE) {
            int index = stage.ordinal();
            long elapsed = now - stageStarted;
            counts[index]++;
            totals[index] += elapsed;
            maxima[index] = Math.max(maxima[index], elapsed);
        }
        if (newFrame) frame++;
        stage = next;
        stageStarted = now;
        revision++;
    }

    private static Snapshot snapshot(boolean includeTotals) {
        for (int attempt = 0; attempt < 3; attempt++) {
            long before = revision;
            if ((before & 1) != 0) continue;
            Snapshot result = new Snapshot(stage, stageStarted, frame,
                    includeTotals ? counts.clone() : null,
                    includeTotals ? totals.clone() : null,
                    includeTotals ? maxima.clone() : null);
            if (before == revision) return result;
        }
        return null;
    }

    private static void watch() {
        System.err.println("[AtomChat render-stall] enabled; CPU phase timing only; "
                + "1s threshold, 250ms polling, no graphics calls or process termination");
        long lastSummary = System.nanoTime();
        Snapshot stalled = null;
        while (renderThread.isAlive()) {
            try {
                Thread.sleep(250);
                long now = System.nanoTime();
                boolean report = now - lastSummary >= SUMMARY_NANOS;
                Snapshot current = snapshot(report);
                if (current == null) continue;
                if (stalled != null && (current.started != stalled.started || current.stage != stalled.stage)) {
                    System.err.println("[AtomChat render-stall] recovered stage=" + stalled.stage
                            + " frame=" + stalled.frame + " observedMs=" + millis(now - stalled.started)
                            + " (poll-resolution upper bound), now=" + current.stage);
                    stalled = null;
                }
                if (stalled == null && current.stage.watch && current.started != 0
                        && now - current.started >= STALL_NANOS) {
                    stalled = current;
                    System.err.println("[AtomChat render-stall] stalled stage=" + current.stage
                            + " frame=" + current.frame + " elapsedMs=" + millis(now - current.started)
                            + "; cold initialization can also cross this threshold");
                    ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(renderThread.threadId(), 64);
                    if (info != null) {
                        System.err.println("[AtomChat render-stall] thread=" + info.getThreadName()
                                + " state=" + info.getThreadState() + " lock=" + info.getLockName());
                        for (StackTraceElement element : info.getStackTrace()) {
                            System.err.println("[AtomChat render-stall]   at " + element);
                        }
                    }
                }
                if (report) {
                    lastSummary = now;
                    StringBuilder row = new StringBuilder("[AtomChat render-stall] cumulative frame=")
                            .append(current.frame).append(" current=").append(current.stage);
                    for (Stage item : Stage.values()) {
                        int index = item.ordinal();
                        if (current.counts[index] == 0) continue;
                        row.append(' ').append(item).append("[n=").append(current.counts[index])
                                .append(",meanMs=").append(millis(current.totals[index] / current.counts[index]))
                                .append(",maxMs=").append(millis(current.maxima[index])).append(']');
                    }
                    System.err.println(row);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                System.err.println("[AtomChat render-stall] diagnostics stopped: " + failure);
                return;
            }
        }
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private record Snapshot(Stage stage, long started, long frame,
                            long[] counts, long[] totals, long[] maxima) { }
}
