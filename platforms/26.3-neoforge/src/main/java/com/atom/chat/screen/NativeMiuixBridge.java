package com.atom.chat.screen;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.miuix.NativeMiuixRuntime;
import com.atom.chat.miuix.api.MiuixHost;
import com.atom.chat.render.PanelBlurRenderer;
import com.atom.chat.render.NativeSceneGpuTarget;
import com.atom.chat.render.NativeWorldGpuSource;
import com.atom.chat.render.SharedGlSession;
import com.atom.chat.render.SkiaGraphics;
import com.atom.chat.ui.PanelOpenMotion;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Pixmap;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;

import java.awt.event.KeyEvent;
import java.util.List;

/** Owns the Compose scene and shares a host FBO across independent Skia bindings. */
final class NativeMiuixBridge implements AutoCloseable {
    private final NativeMiuixRuntime runtime;
    private Image raster;
    private long generation = Long.MIN_VALUE;
    private float panelX;
    private float panelY;
    private float density = 1;
    private float guiScale = 1;
    private float lastPointerX = Float.NaN;
    private float lastPointerY = Float.NaN;
    private int lastModifiers = -1;
    private Boolean windowFocused;
    private NativeSceneGpuTarget gpuTarget;
    private SharedGlSession gpuSession;
    private boolean closing;
    private boolean retainContextOnTargetClose;
    private boolean gpuFailed;
    private boolean sceneDirty = true;
    private long preparedNanos = Long.MIN_VALUE;
    private float paintedX = Float.NaN, paintedY, paintedWidth, paintedHeight, paintedScale, paintedProgress;
    private AtomChatConfig.PageNavStyle paintedStyle;
    private long gpuSnapshots;
    private long drawCalls;
    private long pixelCopies;
    private long copiedBytes;
    private long worldUpdateNanos;
    private long composeRenderNanos;
    private long pixelCopyNanos;
    private NativeWorldGpuSource worldGpuSource;
    private final java.util.ArrayList<NativeWorldGpuSource> retiredWorldGpuSources = new java.util.ArrayList<>();

    NativeMiuixBridge(MiuixHost host) {
        runtime = new NativeMiuixRuntime(host);
    }

    void draw(Canvas canvas, float x, float y, float width, float height,
              float scale, float gameGuiScale, float progress, long nanos) {
        if (preparedNanos != nanos) {
            prepare(x, y, width, height, scale, gameGuiScale, progress, nanos);
        }
        int pixelWidth = Math.max(1, Math.round(width * scale));
        int pixelHeight = Math.max(1, Math.round(height * scale));
        SharedGlSession session = SkiaGraphics.currentGpuSession();
        boolean gpu = session != null && !gpuFailed;
        long started = System.nanoTime();
        if (gpu) {
            try {
                if (gpuTarget == null || !gpuTarget.validFor(session, pixelWidth, pixelHeight)) {
                    closeGpuTarget(gpuSession == session);
                    gpuSession = session;
                    gpuTarget = new NativeSceneGpuTarget(session, pixelWidth, pixelHeight,
                            () -> releaseGpuFrame(session));
                    sceneDirty = true;
                }
                if (sceneDirty || raster == null) {
                    if (worldGpuSource != null && worldGpuSource.nativeContext()
                            != org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext()) {
                        throw new IllegalStateException("World framebuffer belongs to another OpenGL context");
                    }
                    gpuTarget.beforeProducerDraw();
                    long next = runtime.renderGpu(gpuTarget.framebuffer(), pixelWidth, pixelHeight, scale,
                            nanos, gpuTarget.contextToken());
                    releaseRetiredWorldGpuSources();
                    if (generation != next || raster == null) {
                        Image image = gpuTarget.snapshot();
                        if (raster != null) raster.close();
                        raster = image;
                        generation = next;
                        gpuSnapshots++;
                    } else {
                        session.afterExternalDraw();
                    }
                }
            } catch (RuntimeException | LinkageError failure) {
                gpuFailed = true;
                gpu = false;
                PanelBlurRenderer.disableNativeGpuCapture();
                try {
                    if (session.isOpen()) session.afterExternalDraw();
                } catch (RuntimeException | LinkageError cleanup) {
                    failure.addSuppressed(cleanup);
                }
                try {
                    closeGpuTarget();
                } catch (RuntimeException | LinkageError cleanup) {
                    failure.addSuppressed(cleanup);
                }
                com.atom.chat.AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message(
                        "render", "Miuix GPU renderer unavailable, using raster fallback"), failure);
            }
        }
        if (!gpu) {
            closeGpuTarget();
            NativeMiuixRuntime.PixelFrame frame = runtime.render(pixelWidth, pixelHeight, scale, nanos);
            if (frame == null) return;
            if (generation != frame.generation() || raster == null) {
                long copyStarted = System.nanoTime();
                // Copy raw pixels only on the compatibility path, never a Skia object pointer.
                try (Pixmap pixels = Pixmap.make(new ImageInfo(frame.width(), frame.height(),
                        ColorType.BGRA_8888, ColorAlphaType.PREMUL), frame.address(), frame.rowBytes())) {
                    Image image = Image.makeRasterFromPixmap(pixels);
                    if (raster != null) raster.close();
                    raster = image;
                    generation = frame.generation();
                }
                pixelCopyNanos += System.nanoTime() - copyStarted;
                pixelCopies++;
                copiedBytes += (long) frame.width() * frame.height() * 4;
            }
        }
        composeRenderNanos += System.nanoTime() - started;
        drawCalls++;
        sceneDirty = false;
        if (raster == null) return;
        float openScale = PanelOpenMotion.openScale(progress, AtomChatConfig.get().pageNavStyle);
        float slide = PanelOpenMotion.openSlidePx(progress, AtomChatConfig.get().pageNavStyle);
        float cx = x + width / 2, cy = y + height / 2;
        canvas.save();
        try (Paint paint = new Paint().setAlphaf(PanelOpenMotion.fade(progress))) {
            canvas.translate(cx + slide, cy);
            canvas.scale(openScale, openScale);
            canvas.translate(-cx, -cy);
            canvas.drawImageRect(raster, Rect.makeWH(pixelWidth, pixelHeight),
                    Rect.makeXYWH(x, y, width, height), SamplingMode.LINEAR, paint, false);
        } finally {
            canvas.restore();
        }
        paintedX = x; paintedY = y; paintedWidth = width; paintedHeight = height;
        paintedScale = scale; paintedProgress = progress; paintedStyle = AtomChatConfig.get().pageNavStyle;
    }

    /** Input must advance even when the already-composited texture can be retained. */
    boolean prepareWithInput(float x, float y, float width, float height, float scale, float gameGuiScale,
                             float progress, long nanos, boolean focused, double mouseX, double mouseY, int modifiers) {
        panelX = x; panelY = y; density = scale; guiScale = gameGuiScale;
        runtime.resizeViewport(Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), scale);
        focused(focused);
        if (focused) pointer(0, mouseX, mouseY, 0, 0, -1, modifiers);
        return prepare(x, y, width, height, scale, gameGuiScale, progress, nanos);
    }

    /** Advances composition outside the GL update pass. */
    boolean prepare(float x, float y, float width, float height, float scale,
                    float gameGuiScale, float progress, long nanos) {
        panelX = x; panelY = y; density = scale; guiScale = gameGuiScale;
        int pixelWidth = Math.max(1, Math.round(width * scale));
        int pixelHeight = Math.max(1, Math.round(height * scale));
        float openScale = PanelOpenMotion.openScale(progress, AtomChatConfig.get().pageNavStyle);
        float slide = PanelOpenMotion.openSlidePx(progress, AtomChatConfig.get().pageNavStyle);
        float cx = x + width / 2, cy = y + height / 2;
        PanelBlurRenderer.nativeWorldBounds((cx + slide - width * openScale / 2) * scale,
                (cy - height * openScale / 2) * scale, width * openScale * scale, height * openScale * scale);
        PanelBlurRenderer.WorldFrame world = PanelBlurRenderer.worldFrame();
        NativeWorldGpuSource gpuWorld = PanelBlurRenderer.gpuWorldFrame();
        long started = System.nanoTime();
        if (gpuWorld != null && !gpuFailed) {
            if (worldGpuSource != gpuWorld) {
                if (worldGpuSource != null) retiredWorldGpuSources.add(worldGpuSource);
                worldGpuSource = gpuWorld.retain();
            }
            runtime.setWorldGpuFrame(gpuWorld.framebuffer(), gpuWorld.width(), gpuWorld.height(),
                    PanelBlurRenderer.worldSequence(),
                    (cx + slide - width * openScale / 2) * scale,
                    (cy - height * openScale / 2) * scale, width * openScale * scale, height * openScale * scale);
        } else if (world != null) runtime.setWorldFrame(world.width(), world.height(), world.rgba(), world.sequence(),
                (cx + slide - width * openScale / 2) * scale - world.originX(),
                (cy - height * openScale / 2) * scale - world.originY(), width * openScale * scale, height * openScale * scale);
        worldUpdateNanos += System.nanoTime() - started;
        sceneDirty = runtime.prepareFrame(pixelWidth, pixelHeight, scale, nanos);
        preparedNanos = nanos;
        return sceneDirty || raster == null || x != paintedX || y != paintedY || width != paintedWidth
                || height != paintedHeight || scale != paintedScale || progress != paintedProgress
                || AtomChatConfig.get().pageNavStyle != paintedStyle;
    }

    /** Development counters sampled outside timed frames by the benchmark harness. */
    java.util.Map<String, Object> performanceSnapshot() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>(runtime.statistics());
        result.put("drawCalls", drawCalls); result.put("pixelCopies", pixelCopies);
        result.put("copiedBytes", copiedBytes); result.put("worldUpdateNanos", worldUpdateNanos);
        result.put("composeRenderNanos", composeRenderNanos); result.put("pixelCopyNanos", pixelCopyNanos);
        result.put("generation", generation); result.put("gpuSnapshots", gpuSnapshots);
        result.put("gpuActive", gpuTarget != null && !gpuFailed);
        result.put("worldSequence", PanelBlurRenderer.worldSequence());
        result.put("worldGpuFrames", PanelBlurRenderer.worldGpuFrames());
        result.put("worldGpuProbes", PanelBlurRenderer.worldGpuProbes());
        result.put("worldReadbackFrames", PanelBlurRenderer.worldReadbackFrames());
        result.put("worldReadbackBytes", PanelBlurRenderer.worldReadbackBytes());
        return result;
    }

    private void releaseGpuFrame(SharedGlSession session) {
        boolean targetOnly = retainContextOnTargetClose && !closing && !gpuFailed && session.isOpen();
        Image previous = raster;
        raster = null;
        generation = Long.MIN_VALUE;
        try {
            if (previous != null) previous.close();
        } finally {
            try {
                if (targetOnly) {
                    runtime.releaseGpuTarget();
                } else if (!closing) {
                    runtime.releaseGpu();
                }
            } finally {
                if (!targetOnly) releaseRetiredWorldGpuSources();
            }
        }
    }

    private void releaseRetiredWorldGpuSources() {
        for (NativeWorldGpuSource source : retiredWorldGpuSources) source.close();
        retiredWorldGpuSources.clear();
    }

    private void closeGpuTarget() {
        closeGpuTarget(false);
    }

    private void closeGpuTarget(boolean retainContext) {
        NativeSceneGpuTarget target = gpuTarget;
        gpuTarget = null;
        boolean previous = retainContextOnTargetClose;
        retainContextOnTargetClose = retainContext;
        try {
            if (target != null) target.close();
        } finally {
            retainContextOnTargetClose = previous;
        }
    }

    void pointer(int kind, double x, double y, double scrollX, double scrollY, int button, int modifiers) {
        float localX = (float) x * guiScale - panelX * density;
        float localY = (float) y * guiScale - panelY * density;
        if (kind == 0 && localX == lastPointerX && localY == lastPointerY && modifiers == lastModifiers) return;
        lastPointerX = localX; lastPointerY = localY; lastModifiers = modifiers;
        runtime.pointer(kind, localX, localY,
                (float) -scrollX, (float) -scrollY, button, modifiers);
    }

    boolean key(int legacyKey, boolean down, int modifiers) {
        int key = switch (legacyKey) {
            case 256 -> KeyEvent.VK_ESCAPE;
            case 257, 335 -> KeyEvent.VK_ENTER;
            case 258 -> KeyEvent.VK_TAB;
            case 259 -> KeyEvent.VK_BACK_SPACE;
            case 260 -> KeyEvent.VK_INSERT;
            case 261 -> KeyEvent.VK_DELETE;
            case 262 -> KeyEvent.VK_RIGHT;
            case 263 -> KeyEvent.VK_LEFT;
            case 264 -> KeyEvent.VK_DOWN;
            case 265 -> KeyEvent.VK_UP;
            case 266 -> KeyEvent.VK_PAGE_UP;
            case 267 -> KeyEvent.VK_PAGE_DOWN;
            case 268 -> KeyEvent.VK_HOME;
            case 269 -> KeyEvent.VK_END;
            case 340, 344 -> KeyEvent.VK_SHIFT;
            case 341, 345 -> KeyEvent.VK_CONTROL;
            case 342, 346 -> KeyEvent.VK_ALT;
            case 343, 347 -> KeyEvent.VK_META;
            default -> legacyKey >= 290 && legacyKey <= 301 ? KeyEvent.VK_F1 + legacyKey - 290 : legacyKey;
        };
        return runtime.key(key, legacyKey == 335 ? KeyEvent.KEY_LOCATION_NUMPAD : KeyEvent.KEY_LOCATION_STANDARD,
                down, modifiers);
    }

    boolean text(String text) { return runtime.text(text); }
    boolean hasTextFocus() { return runtime.hasTextFocus(); }
    boolean isComposingText() { return runtime.isComposingText(); }
    void composingText(String text) { runtime.composingText(text); }
    void composingText(String text, int caretPosition) { runtime.composingText(text, caretPosition); }
    void cancelComposingText() { runtime.cancelComposingText(); }
    void finishComposingText() { runtime.finishComposingText(); }
    void focused(boolean focused) {
        if (windowFocused != null && windowFocused == focused) return;
        windowFocused = focused;
        runtime.setFocused(focused);
        lastPointerX = Float.NaN; lastPointerY = Float.NaN; lastModifiers = -1;
    }

    /** Convert a Compose focused rectangle into the hidden Minecraft EditBox anchor. */
    float[] guiRect(float x, float y, float width, float height) {
        return new float[]{(panelX * density + x) / guiScale, (panelY * density + y) / guiScale,
                width / guiScale, height / guiScale};
    }

    List<String> tags() { return runtime.getTags(); }

    float[] boundsForTag(String tag) {
        float[] rect = runtime.boundsForTag(tag);
        if (rect == null) return null;
        return new float[]{(panelX * density + rect[0]) / guiScale, (panelY * density + rect[1]) / guiScale,
                (panelX * density + rect[2]) / guiScale, (panelY * density + rect[3]) / guiScale};
    }

    float[] boundsForText(String text) {
        float[] rect = runtime.boundsForText(text);
        if (rect == null) return null;
        return new float[]{(panelX * density + rect[0]) / guiScale, (panelY * density + rect[1]) / guiScale,
                (panelX * density + rect[2]) / guiScale, (panelY * density + rect[3]) / guiScale};
    }

    @Override public void close() {
        if (closing) return;
        closing = true;
        SharedGlSession owner = gpuSession;
        Throwable failure = cleanup(null, () -> {
            if (owner != null && owner.isOpen()) {
                owner.inContext(() -> {
                    owner.beforeExternalDraw();
                    try {
                        // Dispose retained Compose graphics layers before their GPU context.
                        runtime.close();
                    } finally {
                        owner.afterExternalDraw();
                    }
                });
            } else {
                runtime.close();
            }
        });
        failure = cleanup(failure, this::closeGpuTarget);
        failure = cleanup(failure, this::releaseRetiredWorldGpuSources);
        NativeWorldGpuSource previousWorld = worldGpuSource;
        worldGpuSource = null;
        if (previousWorld != null) failure = cleanup(failure, previousWorld::close);
        Image previousRaster = raster;
        raster = null;
        if (previousRaster != null) failure = cleanup(failure, previousRaster::close);
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof LinkageError error) throw error;
    }

    private static Throwable cleanup(Throwable failure, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError error) {
            if (failure == null) return error;
            failure.addSuppressed(error);
        }
        return failure;
    }
}
