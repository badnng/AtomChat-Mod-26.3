package com.atom.chat.automation;

import com.atom.chat.diagnostics.FrameProfile;
import com.atom.chat.render.SkiaGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observational development probe: frame starts, not client ticks or an FPS counter. */
final class AutomationPerformance {
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final List<Long> INTERVALS = new ArrayList<>();
    private static final long SAMPLE_MS = Math.max(1000L,
            Long.getLong("atomchat.automation.sampleSeconds", 15L) * 1000L);
    private static String phase;
    private static long started;
    private static long previous;
    private static SkiaGraphics graphics;

    private AutomationPerformance() { }

    static long warmupMs() {
        return Math.max(1000L, Long.getLong("atomchat.automation.warmupSeconds", 5L) * 1000L);
    }

    static int timeoutSeconds() { return (int) (SAMPLE_MS / 1000L + 30L); }

    static void begin(String name) {
        begin(name, null);
    }

    static void begin(String name, SkiaGraphics panelGraphics) {
        phase = name;
        graphics = panelGraphics;
        INTERVALS.clear();
        previous = 0L;
        started = System.nanoTime();
        try {
            var reset = FrameProfile.class.getDeclaredMethod("reset");
            reset.setAccessible(true);
            reset.invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot reset development frame profile", error);
        }
        FrameProfile.setEnabled(true);
    }

    static void frame(RenderFrameEvent.Pre event) {
        if (phase == null) return;
        long now = System.nanoTime();
        if (previous != 0L) INTERVALS.add(now - previous);
        previous = now;
    }

    static boolean complete() {
        if ((System.nanoTime() - started) / 1_000_000L < SAMPLE_MS) return false;
        if (INTERVALS.isEmpty()) throw new AssertionError("No rendered frame intervals captured");
        long elapsed = System.nanoTime() - started;
        var sorted = INTERVALS.stream().mapToLong(Long::longValue).sorted().toArray();
        var window = Minecraft.getInstance().getWindow();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("phase", phase);
        row.put("width", window.getWidth());
        row.put("height", window.getHeight());
        var device = RenderSystem.getDevice().getDeviceInfo();
        var requestedApi = System.getenv().getOrDefault("ATOMCHAT_AUTOMATION_GRAPHICS_API", "unspecified");
        var backendName = device.backendName();
        boolean matches = requestedApi.equals("unspecified")
                || (requestedApi.equals("prefer-vulkan") ? backendName.toLowerCase(java.util.Locale.ROOT).contains("vulkan")
                : backendName.toLowerCase(java.util.Locale.ROOT).contains("opengl"));
        row.put("requestedGraphicsApi", requestedApi);
        row.put("startupGraphicsPreference", Minecraft.getInstance().options.preferredGraphicsBackend().get().getSerializedName());
        row.put("activeGraphicsBackend", backendName);
        row.put("graphicsDevice", device.name());
        row.put("graphicsDriver", device.driverInfo());
        row.put("backendMatchesRequest", matches);
        row.put("graphicsBackendFallback", !matches);
        if (graphics != null) {
            row.put("renderBackend", graphics.rendererBackend());
            row.put("surfaceWidth", graphics.requireSurface().getWidth());
            row.put("surfaceHeight", graphics.requireSurface().getHeight());
        } else {
            row.put("renderBackend", "no-panel");
        }
        row.put("intervalCount", sorted.length);
        row.put("elapsedMs", elapsed / 1_000_000.0);
        row.put("frameIntervalP50Ms", percentile(sorted, 0.50));
        row.put("frameIntervalP95Ms", percentile(sorted, 0.95));
        row.put("frameIntervalMaxMs", sorted[sorted.length - 1] / 1_000_000.0);
        row.put("frameIntervalMeanMs", INTERVALS.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0);
        row.put("frameProfile", FrameProfile.summary());
        row.put("timingSource", "System.nanoTime at RenderFrameEvent.Pre; client frame extraction cadence, not GPU presentation timestamps");
        row.put("requestedFpsOption", 260);
        row.put("pacingNote", "260 is the vanilla maximum/unlimited setting; this probe does not impose a 260 FPS cap or measure display presentation");
        row.put("vsyncRequested", false);
        row.put("actualFpsLimit", Minecraft.getInstance().getFramerateLimitTracker().getFramerateLimit());
        row.put("throttleReason", Minecraft.getInstance().getFramerateLimitTracker().getThrottleReason().name());
        row.put("vsyncOption", Minecraft.getInstance().options.enableVsync().get());
        row.put("windowIconified", window.isIconified());
        row.put("windowFocused", window.isFocused());
        RESULTS.add(row);
        System.out.println("[AtomChat performance] " + row);
        phase = null;
        graphics = null;
        return true;
    }

    private static double percentile(long[] sorted, double percentile) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1)] / 1_000_000.0;
    }

    static List<Map<String, Object>> results() { return RESULTS; }
}
