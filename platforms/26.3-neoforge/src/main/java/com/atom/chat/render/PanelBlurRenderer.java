package com.atom.chat.render;

import com.atom.chat.AtomChat;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Pixmap;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Backend-neutral panel blur. Readback runs after the world and before GUI
 * composition. Its asynchronous result supplies the next extracted GUI frame.
 */
public final class PanelBlurRenderer {
    private static Image blurredWorld;
    private static boolean capturePending;
    private static boolean nextRefresh = true;
    private static boolean disabled;
    private static int generation;
    private static int worldWidth;
    private static int worldHeight;
    private static long capturedFrames;

    private PanelBlurRenderer() {
    }

    /** Called by GuiRendererWorldCaptureMixin before vanilla GUI composition. */
    public static void captureWorld() {
        Minecraft client = Minecraft.getInstance();
        if (!(client.gui.screen() instanceof com.atom.chat.screen.AtomChatScreen)
                || !com.atom.chat.config.AtomChatConfig.get().blurEnabled
                || com.atom.chat.wallpaper.WallpaperStore.isSet() || disabled) {
            if (blurredWorld != null && !(client.gui.screen() instanceof com.atom.chat.screen.AtomChatScreen)) {
                releaseWorldSnapshot();
            }
            return;
        }
        var target = client.gameRenderer.mainRenderTarget();
        boolean resized = worldWidth != target.width || worldHeight != target.height;
        if (resized) {
            releaseWorldSnapshot();
            worldWidth = target.width;
            worldHeight = target.height;
        }
        if (capturePending) {
            return;
        }
        boolean refresh = BlurMotionGate.allowFullRefresh(System.currentTimeMillis(), nextRefresh,
                blurredWorld == null);
        nextRefresh = !refresh;
        if (!refresh) {
            return;
        }
        // Vanilla handles GPU format, asynchronous mapping, and the Y flip.
        // Integer box downsampling keeps the cached Gaussian inexpensive.
        int downscale = 4;
        while (downscale > 1 && (target.width % downscale != 0 || target.height % downscale != 0)) {
            downscale--;
        }
        int requestGeneration = generation;
        int captureWidth = target.width;
        int captureHeight = target.height;
        capturePending = true;
        try {
            int factor = downscale;
            Screenshot.takeScreenshot(target, factor, pixels -> {
                try (pixels) {
                    if (requestGeneration != generation) {
                        return;
                    }
                    try (Pixmap pixmap = Pixmap.make(new ImageInfo(pixels.getWidth(), pixels.getHeight(),
                            ColorType.RGBA_8888, ColorAlphaType.OPAQUE), pixels.getPointer(), pixels.getWidth() * 4);
                         Image world = Image.makeRasterFromPixmap(pixmap);
                         // Keep Gaussian work at quarter resolution even when
                         // an odd window size prevents vanilla downsampling.
                         Surface blurred = Surface.makeRasterN32Premul(Math.max(1, (captureWidth + 3) / 4),
                                 Math.max(1, (captureHeight + 3) / 4));
                         ImageFilter filter = ImageFilter.makeBlur(9.0F / 4.0F, 9.0F / 4.0F, FilterTileMode.CLAMP);
                         Paint paint = new Paint().setImageFilter(filter)) {
                        blurred.getCanvas().clear(0);
                        blurred.getCanvas().drawImageRect(world, Rect.makeWH(world.getWidth(), world.getHeight()),
                                Rect.makeWH(blurred.getWidth(), blurred.getHeight()), SamplingMode.LINEAR, paint, false);
                        Image next = blurred.makeImageSnapshot();
                        if (blurredWorld != null) {
                            blurredWorld.close();
                        }
                        blurredWorld = next;
                        capturedFrames++;
                    }
                } catch (Throwable error) {
                    disable(error.getClass().getSimpleName());
                    AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("render", "World blur readback failed"), error);
                } finally {
                    if (requestGeneration == generation) {
                        capturePending = false;
                    }
                }
            });
        } catch (Throwable error) {
            capturePending = false;
            disable(error.getClass().getSimpleName());
            AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("render", "World blur capture failed"), error);
        }
    }

    public static boolean render(Matrix4f pose, float x, float y, float width, float height,
                                 float radius, float alpha) {
        Canvas canvas = SkiaGraphics.prepassCanvas();
        if (disabled || blurredWorld == null || canvas == null || alpha <= 0.003F || width <= 0 || height <= 0) {
            return false;
        }
        float scale = Minecraft.getInstance().getWindow().getGuiScale();
        Vector4f origin = pose.transform(new Vector4f(x, y, 0.0F, 1.0F));
        float left = origin.x() * scale;
        float top = origin.y() * scale;
        float w = width * Math.abs(pose.m00()) * scale;
        float h = height * Math.abs(pose.m11()) * scale;
        int baseline = canvas.getSaveCount();
        canvas.save();
        try (Paint paint = new Paint().setAlphaf(Math.clamp(alpha, 0.0F, 1.0F))) {
            canvas.clipRRect(RRect.makeXYWH(left, top, w, h,
                    Math.min(radius * scale, Math.min(w, h) / 2.0F)), true);
            canvas.drawImageRect(blurredWorld, Rect.makeWH(blurredWorld.getWidth(), blurredWorld.getHeight()),
                    Rect.makeWH(worldWidth, worldHeight), SamplingMode.LINEAR, paint, false);
            return true;
        } finally {
            canvas.restoreToCount(baseline);
        }
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
    }

    public static void releaseWorldSnapshot() {
        generation++;
        capturePending = false;
        nextRefresh = true;
        if (blurredWorld != null) {
            blurredWorld.close();
            blurredWorld = null;
        }
    }
}
