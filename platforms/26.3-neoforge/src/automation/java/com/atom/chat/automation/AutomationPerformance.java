package com.atom.chat.automation;

import com.atom.chat.diagnostics.FrameProfile;
import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.render.PanelBlurRenderer;
import com.atom.chat.screen.AtomChatScreen;
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
    private static AtomChatScreen panel;
    private static long capturesBefore;
    private static long renderedBefore;
    private static Map<String, Object> nativeBefore;

    static AtomChatConfig.UiStyle uiStyle() {
        return AtomChatConfig.UiStyle.valueOf(System.getProperty("atomchat.automation.uiStyle", "atomchat")
                .toUpperCase(java.util.Locale.ROOT));
    }

    static boolean profilePage() {
        return System.getProperty("atomchat.automation.profile", "world-chat").equals("profile");
    }

    private AutomationPerformance() { }

    static long warmupMs() {
        return Math.max(1000L, Long.getLong("atomchat.automation.warmupSeconds", 5L) * 1000L);
    }

    static int timeoutSeconds() { return (int) (SAMPLE_MS / 1000L + 30L); }

    static void begin(String name) {
        begin(name, null, null);
    }

    static void begin(String name, SkiaGraphics panelGraphics, AtomChatScreen screen) {
        phase = name;
        graphics = panelGraphics;
        panel = screen;
        capturesBefore = PanelBlurRenderer.capturedFrames();
        renderedBefore = SkiaGraphics.renderedFrames();
        nativeBefore = nativeSnapshot();
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
        var config = AtomChatConfig.get();
        row.put("mode", "performance");
        row.put("profile", System.getProperty("atomchat.automation.profile", "world-chat"));
        row.put("uiStyle", config.effectiveUiStyle().name());
        row.put("blurEnabled", config.blurEnabled);
        row.put("uiScale", config.uiScale);
        row.put("panelHeight", config.panelHeight);
        row.put("warmupMs", warmupMs());
        row.put("requestedSampleMs", SAMPLE_MS);
        row.put("worldBlurCapturesBefore", capturesBefore);
        row.put("worldBlurCapturesAfter", PanelBlurRenderer.capturedFrames());
        row.put("worldBlurCaptureDelta", PanelBlurRenderer.capturedFrames() - capturesBefore);
        row.put("panelDrawDelta", SkiaGraphics.renderedFrames() - renderedBefore);
        row.put("nativeBefore", nativeBefore);
        Map<String, Object> nativeAfter = nativeSnapshot();
        row.put("nativeAfter", nativeAfter);
        Map<String, Object> nativeDelta = new LinkedHashMap<>();
        nativeAfter.forEach((key, value) -> {
            if (value instanceof Number after && nativeBefore.get(key) instanceof Number before) {
                nativeDelta.put(key, after.doubleValue() - before.doubleValue());
            }
        });
        row.put("nativeDelta", nativeDelta);
        row.put("panelProcessing", panelProfile());
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
        boolean visible = !Boolean.parseBoolean(System.getProperty("atomchat.automation.hideWindow", "true"));
        row.put("requestedFpsOption", visible ? 60 : 260);
        row.put("pacingNote", visible ? "Visible inspection requests V-Sync and a 60 FPS cap; no display presentation timestamps are measured"
                : "260 is the vanilla maximum/unlimited setting; this probe does not impose a 260 FPS cap or measure display presentation");
        row.put("vsyncRequested", visible);
        row.put("actualFpsLimit", Minecraft.getInstance().getFramerateLimitTracker().getFramerateLimit());
        row.put("throttleReason", Minecraft.getInstance().getFramerateLimitTracker().getThrottleReason().name());
        row.put("vsyncOption", Minecraft.getInstance().options.enableVsync().get());
        row.put("windowIconified", window.isIconified());
        row.put("windowFocused", window.isFocused());
        RESULTS.add(row);
        System.out.println("[AtomChat performance] " + row);
        phase = null;
        graphics = null;
        panel = null;
        return true;
    }

    /** Optional development API: old production code still yields usable baseline timings. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nativeSnapshot() {
        if (panel == null) return Map.of("available", false, "reason", "no-panel");
        try {
            var field = AtomChatScreen.class.getDeclaredField("miuixBridge");
            field.setAccessible(true);
            Object bridge = field.get(panel);
            if (bridge == null) return Map.of("available", false, "reason", "no-native-bridge");
            var method = bridge.getClass().getDeclaredMethod("performanceSnapshot");
            method.setAccessible(true);
            return new LinkedHashMap<>((Map<String, Object>) method.invoke(bridge));
        } catch (ReflectiveOperationException error) {
            return Map.of("available", false, "reason", error.getClass().getSimpleName());
        }
    }

    /** Read the existing rolling bucket without extending production diagnostics APIs. */
    private static Map<String, Object> panelProfile() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            var bucketField = FrameProfile.class.getDeclaredField("PANEL_BUCKET");
            bucketField.setAccessible(true);
            Object bucket = bucketField.get(null);
            var type = bucket.getClass();
            var nanosField = type.getDeclaredField("nanos");
            var sizeField = type.getDeclaredField("size");
            var allocatedField = type.getDeclaredField("allocatedTotal");
            var allocatedSamplesField = type.getDeclaredField("allocatedSamples");
            for (var field : List.of(nanosField, sizeField, allocatedField, allocatedSamplesField)) field.setAccessible(true);
            int size = sizeField.getInt(bucket);
            long[] values = java.util.Arrays.copyOf((long[]) nanosField.get(bucket), size);
            java.util.Arrays.sort(values);
            result.put("sampleCount", size);
            result.put("scope", "SkiaGraphics.draw including native Miuix; last 4096 panel draws");
            if (size > 0) {
                result.put("p50Ms", percentile(values, 0.50));
                result.put("p95Ms", percentile(values, 0.95));
                result.put("maxMs", values[size - 1] / 1_000_000.0);
                result.put("meanMs", java.util.Arrays.stream(values).average().orElse(0) / 1_000_000.0);
            }
            int allocations = allocatedSamplesField.getInt(bucket);
            result.put("allocationSampleCount", allocations);
            if (allocations > 0) result.put("jvmAllocatedBytesPerPanelDraw", allocatedField.getLong(bucket) / (double) allocations);
            result.put("allocationScope", "JVM render-thread wrappers; excludes native allocations");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot inspect development panel profile", error);
        }
        return result;
    }

    private static double percentile(long[] sorted, double percentile) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1)] / 1_000_000.0;
    }

    static List<Map<String, Object>> results() { return RESULTS; }
}
