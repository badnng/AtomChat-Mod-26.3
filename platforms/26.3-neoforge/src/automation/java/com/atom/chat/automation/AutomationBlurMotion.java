package com.atom.chat.automation;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.render.PanelBlurRenderer;
import com.atom.chat.render.PanelRasterBounds;
import com.atom.chat.render.SkiaGraphics;
import com.atom.chat.screen.AtomChatScreen;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Bitmap;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Render-tail evidence; never changes animation progress or production blur state. */
public final class AutomationBlurMotion {
    private static final List<Map<String, Object>> FRAMES = new ArrayList<>();
    private static final List<CompletableFuture<Void>> CAPTURES = new ArrayList<>();
    private static final Set<String> SAVED = new HashSet<>();
    private static AtomChatScreen panel;
    private static Path directory;
    private static String phase;
    private static long start;
    private static long lastSnapshot = -1;
    private static boolean firstPublishedSnapshot;
    private static long resizeSettled;
    private static long openSettled;
    private static final Set<Long> SETTLED_CAPTURES = new HashSet<>();
    private static Throwable failure;

    private AutomationBlurMotion() { }

    static void begin(String name, AtomChatScreen screen, Path frames) throws Exception {
        directory = frames;
        Files.createDirectories(directory);
        panel = screen;
        phase = name;
        firstPublishedSnapshot = true;
        openSettled = 0L;
        SETTLED_CAPTURES.clear();
        start = System.nanoTime();
    }

    static void close() throws Exception {
        var close = AtomChatScreen.class.getDeclaredMethod("requestClose");
        close.setAccessible(true);
        close.invoke(panel);
        phase = phase.startsWith("resized-") ? "close-resize" : phase.replace("open", "close");
        start = System.nanoTime();
    }

    static boolean held() {
        return openSettled != 0L && (System.nanoTime() - openSettled) / 1_000_000L >= 1600L
                && SETTLED_CAPTURES.size() >= 2;
    }

    static void resize() {
        phase = "resized-resize";
        start = System.nanoTime();
        resizeSettled = 0L;
        firstPublishedSnapshot = true;
        SETTLED_CAPTURES.clear();
    }

    static boolean resizeHeld() {
        var window = Minecraft.getInstance().getWindow();
        if (window.getWidth() != 1777 || window.getHeight() != 997) {
            resizeSettled = 0L;
            return false;
        }
        return resizeSettled != 0L && (System.nanoTime() - resizeSettled) / 1_000_000L >= 1600L
                && SETTLED_CAPTURES.size() >= 2;
    }

    static boolean closed() {
        if (Minecraft.getInstance().gui.screen() == panel) return false;
        phase = null;
        return true;
    }

    static boolean complete() {
        if (failure != null) throw new AssertionError("Blur motion capture failed", failure);
        if (CAPTURES.stream().anyMatch(f -> !f.isDone())) return false;
        CAPTURES.forEach(CompletableFuture::join);
        return true;
    }

    /** Called in the same GameRenderer render tail as real compositor readback. */
    public static void afterRender() {
        if (phase == null || failure != null || Minecraft.getInstance().gui.screen() != panel) return;
        try {
            float progress = ((Number) field(AtomChatScreen.class, panel, "panelProgress")).floatValue();
            boolean closing = (boolean) field(AtomChatScreen.class, panel, "closing");
            if (progress == 1.0F && !closing) {
                if (phase.startsWith("open-")) {
                    if (openSettled == 0L) openSettled = System.nanoTime();
                    if (PanelBlurRenderer.capturedFrames() > 0) SETTLED_CAPTURES.add(PanelBlurRenderer.capturedFrames());
                } else if (phase.equals("resized-resize")
                        && Minecraft.getInstance().getWindow().getWidth() == 1777
                        && Minecraft.getInstance().getWindow().getHeight() == 997) {
                    if (resizeSettled == 0L) resizeSettled = System.nanoTime();
                    if (PanelBlurRenderer.capturedFrames() > 0) SETTLED_CAPTURES.add(PanelBlurRenderer.capturedFrames());
                }
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            var graphics = (SkiaGraphics) field(AtomChatScreen.class, panel, "graphics");
            if (graphics == null || !graphics.isReady()) return;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("frame", FRAMES.size());
            row.put("phase", phase);
            row.put("elapsedMs", elapsed);
            long settledSince = phase.equals("resized-resize") ? resizeSettled : openSettled;
            row.put("settledHoldMs", settledSince == 0L ? 0L : (System.nanoTime() - settledSince) / 1_000_000L);
            row.put("panelProgress", progress);
            row.put("closing", closing);
            row.put("blurDrawnThisFrame", field(AtomChatScreen.class, panel, "blurDrawnThisFrame"));
            row.put("snapshotGeneration", field(PanelBlurRenderer.class, null, "generation"));
            row.put("capturePending", field(PanelBlurRenderer.class, null, "capturePending"));
            row.put("hasSnapshot", field(PanelBlurRenderer.class, null, "blurredWorld") != null);
            row.put("capturedFrames", PanelBlurRenderer.capturedFrames());
            row.put("uiScale", AtomChatConfig.get().uiScale);
            row.put("panelHeight", AtomChatConfig.get().panelHeight);
            row.put("viewportWidth", Minecraft.getInstance().getWindow().getWidth());
            row.put("viewportHeight", Minecraft.getInstance().getWindow().getHeight());
            var bounds = (PanelRasterBounds) field(SkiaGraphics.class, graphics, "bounds");
            row.put("bounds", String.valueOf(bounds));
            row.put("boundsY", bounds == null ? -1 : bounds.y());
            row.put("activeGraphicsBackend", RenderSystem.getDevice().getDeviceInfo().backendName());
            row.put("requestedGraphicsApi", System.getenv("ATOMCHAT_AUTOMATION_GRAPHICS_API"));
            row.put("renderBackend", graphics.rendererBackend());
            // Every observed frame gets metadata. PNGs sample progress deciles and
            // settled time buckets, including refresh opportunities beyond 500ms.
            String bucket = progress >= 0.999F && !closing ? "stable-" + elapsed / 500L
                    : "motion-" + Math.min(9, (int) (progress * 10));
            String name = "blur-" + phase + "-" + bucket;
            if (SAVED.add(name)) {
                Files.write(directory.resolve(name + "-surface.png"), graphics.capturePng());
                CAPTURES.add(AutomationPresentationCapture.request(directory.resolve(name + "-composited.png")));
                row.put("artifact", name);
            }
            long captures = PanelBlurRenderer.capturedFrames();
            if (captures != lastSnapshot) {
                var snapshot = (Image) field(PanelBlurRenderer.class, null, "blurredWorld");
                if (snapshot != null) {
                    if (firstPublishedSnapshot) {
                        row.put("firstPublishedSnapshotMaxRGB", maxRgb(snapshot));
                        firstPublishedSnapshot = false;
                    }
                    String nameSnapshot = "blur-snapshot-" + captures + ".png";
                    try (var data = snapshot.encodeToData(EncodedImageFormat.PNG)) {
                        if (data == null) throw new IllegalStateException("Cannot encode blur snapshot");
                        Files.write(directory.resolve(nameSnapshot), data.getBytes());
                    }
                    row.put("snapshotArtifact", nameSnapshot);
                }
                lastSnapshot = captures;
            }
            FRAMES.add(row);
        } catch (Throwable error) { failure = error; phase = null; }
    }

    static List<Map<String, Object>> frames() { return FRAMES; }

    static void verify() {
        if (failure != null) throw new AssertionError("Blur motion capture failed", failure);
        // This generated fixture is a known bright daytime world. Keep black
        // snapshots as evidence and reject them here, never in production code.
        for (var row : FRAMES) {
            if (row.get("firstPublishedSnapshotMaxRGB") instanceof Number maximum && maximum.intValue() < 10) {
                throw new AssertionError("Known bright fixture published an all-black first blur snapshot: "
                        + row.get("phase") + "; maxRGB=" + maximum + "; artifact=" + row.get("snapshotArtifact"));
            }
        }
        for (String cycle : List.of("first", "repeat", "half-scale", "resize")) {
            var opened = FRAMES.stream().filter(r -> ("open-" + cycle).equals(r.get("phase"))).toList();
            if (opened.stream().noneMatch(r -> r.containsKey("firstPublishedSnapshotMaxRGB"))) {
                throw new AssertionError("No first published snapshot evidence: " + cycle);
            }
            if (cycle.equals("half-scale") && opened.stream().anyMatch(r -> ((Number) r.get("uiScale")).floatValue() != 0.5F)) {
                throw new AssertionError("Half-scale fixture did not retain uiScale=0.5");
            }
            if (cycle.equals("half-scale") && opened.stream().noneMatch(r -> ((Number) r.get("boundsY")).intValue() > 0)) {
                throw new AssertionError("Half-scale fixture did not exercise a nonzero raster origin");
            }
            for (String direction : List.of("open", "close")) {
                var motion = FRAMES.stream().filter(r -> (direction + "-" + cycle).equals(r.get("phase")))
                        .filter(r -> ((Number) r.get("panelProgress")).floatValue() > 0.05F
                                && ((Number) r.get("panelProgress")).floatValue() < 0.95F).toList();
                if (motion.size() < 2) throw new AssertionError("Insufficient motion samples: " + direction + "-" + cycle);
                if (motion.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("hasSnapshot"))
                        && !Boolean.TRUE.equals(r.get("blurDrawnThisFrame")))) {
                    throw new AssertionError("Blur absent during motion despite an available capture: " + direction + "-" + cycle);
                }
            }
            var stable = FRAMES.stream().filter(r -> ("open-" + cycle).equals(r.get("phase")))
                    .filter(r -> ((Number) r.get("elapsedMs")).longValue() >= 600).toList();
            if (stable.stream().map(r -> r.get("capturedFrames")).distinct().count() < 2) {
                throw new AssertionError("Stable hold did not exercise blur cache refresh: " + cycle);
            }
        }
        var resized = FRAMES.stream().filter(r -> "resized-resize".equals(r.get("phase"))).toList();
        if (resized.isEmpty()) throw new AssertionError("No actual window resize frames captured");
        if (resized.stream().anyMatch(r -> ((Number) r.get("panelProgress")).floatValue() >= 0.999F
                && (!Boolean.TRUE.equals(r.get("hasSnapshot")) || !Boolean.TRUE.equals(r.get("blurDrawnThisFrame"))))) {
            throw new AssertionError("Fully open panel lost its snapshot or blur during window resize");
        }
        var resizedStable = resized.stream().filter(r -> ((Number) r.get("viewportWidth")).intValue() == 1777
                && ((Number) r.get("viewportHeight")).intValue() == 997).toList();
        if (resizedStable.stream().map(r -> r.get("capturedFrames")).distinct().count() < 2) {
            throw new AssertionError("Resized viewport did not exercise stable blur cache refresh");
        }
        var originalResize = FRAMES.stream().filter(r -> "open-resize".equals(r.get("phase"))).findFirst().orElseThrow();
        if (resized.stream().noneMatch(r -> !r.get("snapshotGeneration").equals(originalResize.get("snapshotGeneration")))) {
            throw new AssertionError("Actual resize did not invalidate the snapshot generation");
        }
    }

    private static Object field(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int maxRgb(Image image) {
        try (var bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(image.getWidth(), image.getHeight());
            if (!image.readPixels(bitmap)) throw new IllegalStateException("Cannot read first blur snapshot");
            int maximum = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int color = bitmap.getColor(x, y);
                    maximum = Math.max(maximum, Math.max((color >>> 16) & 255,
                            Math.max((color >>> 8) & 255, color & 255)));
                }
            }
            return maximum;
        }
    }
}
