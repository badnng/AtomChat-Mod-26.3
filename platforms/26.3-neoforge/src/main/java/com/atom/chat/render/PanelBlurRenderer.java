package com.atom.chat.render;

import com.atom.chat.AtomChat;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;

/**
 * Backend-neutral panel blur. Readback runs after the world and before GUI
 * composition. Its asynchronous result supplies the next extracted GUI frame.
 */
public final class PanelBlurRenderer {
    private static Image blurredWorld;
    private static NativeWorldRegion blurredRegion;
    private static PanelRasterBounds classicBounds;
    private static ClassicWorldBlurSource classicSource;
    private static int classicSnapshotGeneration = -1;
    private static boolean disabled;
    private static int generation;
    private static int worldWidth;
    private static int worldHeight;
    private static long capturedFrames;
    private static WorldFrame unblurredWorld;
    private static long worldSequence;
    private static boolean nativeMiuixCapture;
    private static NativeWorldGpuSource gpuWorld;
    private static boolean gpuWorldFailed;
    private static long lastNativeCaptureNanos;
    private static long worldGpuFrames;
    private static long worldReadbackFrames;
    private static long worldReadbackBytes;
    private static NativeWorldReadbackSource nativeReadback;
    private static float nativeCropX, nativeCropY, nativeCropWidth, nativeCropHeight;

    /** Unfiltered, top-down RGBA world pixels. Consumed only on the render thread. */
    public record WorldFrame(int width, int height, byte[] rgba, long sequence, int originX, int originY) {
    }

    private PanelBlurRenderer() {
    }

    /** Called for rendered world frames, before vanilla GUI composition. */
    public static void captureWorld() {
        Minecraft client = Minecraft.getInstance();
        if (!(client.gui.screen() instanceof com.atom.chat.screen.AtomChatScreen)
                || !com.atom.chat.config.AtomChatConfig.get().blurEnabled
                || com.atom.chat.wallpaper.WallpaperStore.isSet() || disabled) {
            if ((classicSource != null || blurredWorld != null || unblurredWorld != null
                    || gpuWorld != null || nativeReadback != null)
                    && !(client.gui.screen() instanceof com.atom.chat.screen.AtomChatScreen)) {
                releaseWorldSnapshot();
            }
            return;
        }
        boolean miuix = com.atom.chat.config.AtomChatConfig.get().effectiveUiStyle()
                == com.atom.chat.config.AtomChatConfig.UiStyle.MIUIX;
        if (miuix && client.gui.screen() instanceof com.atom.chat.screen.AtomChatScreen screen
                && !screen.nativeWorldBackdropRequired()) return;
        if (nativeMiuixCapture != miuix) {
            generation++;
            nativeMiuixCapture = miuix;
            if (miuix) releaseClassicSource();
            if (!miuix) {
                unblurredWorld = null;
                releaseGpuWorld();
            }
        }
        var target = client.gameRenderer.mainRenderTarget();
        boolean resized = worldWidth != target.width || worldHeight != target.height;
        if (resized) {
            // Retain the previous image until the new-sized readback arrives.
            // Invalidating only the request also keeps old callbacks from
            // clearing the pending state belonging to the new viewport.
            generation++;
            worldWidth = target.width;
            worldHeight = target.height;
            // Republish the first valid region even if a retired callback filled its reusable pixels.
            unblurredWorld = null;
            releaseGpuWorld();
        }
        // Every backend follows world motion at the same 60 Hz cadence.
        long nowNanos = System.nanoTime();
        if (miuix && !gpuWorldFailed
                && target.getColorTexture() instanceof com.mojang.renderpearl.backend.opengl.GlTexture texture) {
            if (!NativeWorldRefreshPolicy.captureDue(nowNanos, lastNativeCaptureNanos)) return;
            lastNativeCaptureNanos = NativeWorldRefreshPolicy.advanceCapture(nowNanos, lastNativeCaptureNanos);
            try {
                if (gpuWorld == null) gpuWorld = new NativeWorldGpuSource(target.width, target.height);
                if (gpuWorld.capture(texture)) {
                    worldSequence++;
                    worldGpuFrames++;
                    capturedFrames++;
                }
                return;
            } catch (RuntimeException | LinkageError failure) {
                gpuWorldFailed = true;
                releaseGpuWorld();
                AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message(
                        "render", "Native world GPU capture unavailable, using bounded readback"), failure);
            }
        }
        if (miuix) {
            if (!NativeWorldRefreshPolicy.captureDue(nowNanos, lastNativeCaptureNanos)) return;
            NativeWorldRegion region = NativeWorldRegion.covering(target.width, target.height,
                    nativeCropX, nativeCropY, nativeCropWidth, nativeCropHeight);
            if (region == null) return; // The first extracted panel supplies its physical sampling bounds.
            if (nativeReadback == null) nativeReadback = new NativeWorldReadbackSource();
            int requestGeneration = generation;
            try {
                if (nativeReadback.capture(target, region, pixels -> {
                    if (requestGeneration != generation) return;
                    if (pixels.changed() || unblurredWorld == null) {
                        unblurredWorld = new WorldFrame(region.width(), region.height(), pixels.rgba(),
                                ++worldSequence, region.x(), region.y());
                    }
                    capturedFrames++;
                    worldReadbackFrames++;
                    worldReadbackBytes += region.byteCount();
                }, error -> {
                    if (requestGeneration == generation) {
                        disable(error.getClass().getSimpleName());
                        AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message(
                                "render", "Native world region readback failed"), error);
                    }
                })) lastNativeCaptureNanos = NativeWorldRefreshPolicy.advanceCapture(nowNanos, lastNativeCaptureNanos);
            } catch (RuntimeException | LinkageError error) {
                disable(error.getClass().getSimpleName());
                AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message(
                        "render", "Native world region capture failed"), error);
            }
            return;
        }
        if (classicSource != null && classicSource.pending()) {
            return;
        }
        if (classicBounds == null) return;
        // Include every open/close pose plus the Gaussian's world-space kernel.
        // NativeWorldRegion aligns the crop to 64 pixels, keeping the 4x4 box
        // sampling grid stationary as the viewport or panel geometry changes.
        int guard = ClassicWorldBlurSource.KERNEL_GUARD;
        NativeWorldRegion region = NativeWorldRegion.covering(target.width, target.height,
                classicBounds.x() - guard, classicBounds.y() - guard,
                classicBounds.width() + guard * 2, classicBounds.height() + guard * 2);
        if (region == null) return;
        boolean refresh = BlurMotionGate.allowFullRefresh(nowNanos, true,
                blurredWorld == null || resized || classicSnapshotGeneration != generation);
        if (!refresh) {
            return;
        }
        int requestGeneration = generation;
        if (classicSource == null) classicSource = new ClassicWorldBlurSource();
        ClassicWorldBlurSource source = classicSource;
        try {
            source.capture(target, region, pixels -> {
                if (requestGeneration != generation) return;
                if (pixels.changed() || blurredWorld == null || classicSnapshotGeneration != requestGeneration) {
                    // Releasing the old snapshot first avoids raster copy-on-write.
                    if (blurredWorld != null) { blurredWorld.close(); blurredWorld = null; }
                    blurredWorld = source.blur(pixels);
                    blurredRegion = region;
                    classicSnapshotGeneration = requestGeneration;
                }
                capturedFrames++;
            }, error -> {
                if (requestGeneration == generation) {
                    disable(error.getClass().getSimpleName());
                    AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("render", "World blur readback failed"), error);
                }
            });
        } catch (Throwable error) {
            disable(error.getClass().getSimpleName());
            AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("render", "World blur capture failed"), error);
        }
    }

    /** Draw within the panel's own fade layer, sampling the stationary world. */
    public static boolean render(Canvas canvas, PanelRasterBounds origin, int viewportWidth, int viewportHeight,
                                 float x, float y, float width, float height, float radius) {
        if (disabled || blurredWorld == null || canvas == null || origin == null
                || viewportWidth <= 0 || viewportHeight <= 0 || width <= 0 || height <= 0) {
            return false;
        }
        int baseline = canvas.getSaveCount();
        canvas.save();
        try (Paint paint = new Paint()) {
            // Establish the moving panel clip before returning to framebuffer
            // coordinates. The caller's saveLayer supplies the only fade.
            canvas.clipRRect(RRect.makeXYWH(x, y, width, height,
                    Math.max(0.0F, Math.min(radius, Math.min(width, height) / 2.0F))), true);
            canvas.resetMatrix();
            canvas.translate(-origin.x(), -origin.y());
            Rect destination = blurredRegion == null ? Rect.makeWH(viewportWidth, viewportHeight)
                    : Rect.makeXYWH(blurredRegion.x(), blurredRegion.y(),
                            blurredWorld.getWidth() * ClassicWorldBlurSource.DOWNSCALE,
                            blurredWorld.getHeight() * ClassicWorldBlurSource.DOWNSCALE);
            canvas.drawImageRect(blurredWorld, Rect.makeWH(blurredWorld.getWidth(), blurredWorld.getHeight()),
                    destination, SamplingMode.LINEAR, paint, false);
            return true;
        } finally {
            canvas.restoreToCount(baseline);
        }
    }

    public static boolean hasSnapshot() {
        return !disabled && (nativeMiuixCapture ? gpuWorld != null || unblurredWorld != null : blurredWorld != null);
    }

    /** Miuix applies the official shader chain to this source, without double blur. */
    public static WorldFrame worldFrame() {
        return disabled || !nativeMiuixCapture
                || !com.atom.chat.config.AtomChatConfig.get().blurEnabled
                || com.atom.chat.wallpaper.WallpaperStore.isSet() ? null : unblurredWorld;
    }

    public static NativeWorldGpuSource gpuWorldFrame() {
        return disabled || !nativeMiuixCapture
                || !com.atom.chat.config.AtomChatConfig.get().blurEnabled
                || com.atom.chat.wallpaper.WallpaperStore.isSet() ? null : gpuWorld;
    }

    public static long worldSequence() { return worldSequence; }
    public static long worldGpuFrames() { return worldGpuFrames; }
    public static long worldGpuProbes() { return 0; }
    public static long worldReadbackFrames() { return worldReadbackFrames; }
    public static long worldReadbackBytes() { return worldReadbackBytes; }

    public static void nativeWorldBounds(float x, float y, float width, float height) {
        nativeCropX = x; nativeCropY = y; nativeCropWidth = width; nativeCropHeight = height;
    }

    public static void classicWorldBounds(PanelRasterBounds bounds) {
        if (!bounds.equals(classicBounds)) generation++;
        classicBounds = bounds;
    }

    public static void disableNativeGpuCapture() {
        gpuWorldFailed = true;
        releaseGpuWorld();
    }

    public static boolean isAvailable() {
        return !disabled;
    }

    public static long capturedFrames() {
        return capturedFrames;
    }

    public static void disable(String reason) {
        if (!disabled) {
            disabled = true;
            AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("render", "Panel blur disabled after failure: {}"), reason);
        }
    }

    public static void resetShader() {
        releaseWorldSnapshot();
        disabled = false;
        gpuWorldFailed = false;
    }

    public static void releaseWorldSnapshot() {
        generation++;
        worldWidth = 0;
        worldHeight = 0;
        unblurredWorld = null;
        nativeMiuixCapture = false;
        lastNativeCaptureNanos = 0;
        releaseGpuWorld();
        if (nativeReadback != null) { nativeReadback.close(); nativeReadback = null; }
        nativeCropX = nativeCropY = nativeCropWidth = nativeCropHeight = 0;
        classicBounds = null;
        releaseClassicSource();
    }

    private static void releaseClassicSource() {
        if (blurredWorld != null) { blurredWorld.close(); blurredWorld = null; }
        blurredRegion = null;
        classicSnapshotGeneration = -1;
        if (classicSource != null) { classicSource.close(); classicSource = null; }
    }

    private static void releaseGpuWorld() {
        NativeWorldGpuSource previous = gpuWorld;
        gpuWorld = null;
        if (previous != null) previous.close();
    }
}
