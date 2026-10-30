package com.atom.chat.render;

import com.atom.chat.AtomChat;
import com.atom.chat.diagnostics.FrameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Pixmap;
import io.github.humbleui.skija.Surface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.util.function.BiConsumer;

/**
 * Raster Skia bridge for RenderPearl's extracted GUI. Minecraft owns all GPU
 * commands, so Vulkan and OpenGL keep their backend state caches intact.
 * Straight-alpha upload matches vanilla GUI blending.
 */
public final class SkiaGraphics implements AutoCloseable {
    private static Canvas prepassCanvas;
    private static long renderedFrames;
    private static boolean frameSubmitted;
    private Surface surface;
    private DynamicTexture texture;
    private Pixmap uploadPixels;
    private int width;
    private int height;
    private long visiblePixelSamples;

    public void checkFrameBufferId() {
        var window = Minecraft.getInstance().getWindow();
        // GameRenderer resizes its main target after extraction. Use the same
        // window dimensions it already extracted for the upcoming render pass.
        if (surface != null && (width != Math.max(1, window.getWidth()) || height != Math.max(1, window.getHeight()))) {
            close();
        }
    }

    public void createSurface() {
        close();
        var window = Minecraft.getInstance().getWindow();
        width = Math.max(1, window.getWidth());
        height = Math.max(1, window.getHeight());
        surface = Surface.makeRaster(new ImageInfo(width, height, ColorType.RGBA_8888,
                ColorAlphaType.PREMUL, ColorSpace.getSRGB()));
        texture = new DynamicTexture("AtomChat Skia GUI", width, height, false);
        NativeImage pixels = texture.getPixels();
        uploadPixels = Pixmap.make(new ImageInfo(width, height, ColorType.RGBA_8888,
                ColorAlphaType.UNPREMUL, ColorSpace.getSRGB()), pixels.getPointer(), width * 4);
    }

    public void draw(GuiGraphicsExtractor extractor, Runnable preUi, float density,
                     BiConsumer<Canvas, Image> renderer) {
        RenderSystem.assertOnRenderThread();
        long profileStart = FrameProfile.start();
        long allocatedBefore = profileStart == 0L ? -1L : FrameProfile.allocatedBytes();
        checkFrameBufferId();
        if (surface == null) {
            createSurface();
        }
        if (density <= 0.0F) {
            density = Math.max(1.0F, height / 1080.0F);
        }
        Canvas canvas = surface.getCanvas();
        canvas.clear(0);
        int baseline = canvas.getSaveCount();
        canvas.save();
        try {
            // The blur coordinates use GUI scale; design density applies only
            // after the blur has drawn into physical framebuffer coordinates.
            prepassCanvas = canvas;
            if (preUi != null) {
                preUi.run();
            }
            prepassCanvas = null;
            canvas.scale(density, density);
            renderer.accept(canvas, null);
        } finally {
            prepassCanvas = null;
            int leaked = canvas.getSaveCount() - (baseline + 1);
            canvas.restoreToCount(baseline);
            if (leaked != 0 && com.atom.chat.config.AtomChatConfig.get().debug) {
                AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("render", "Skia canvas stack imbalance: {}"), leaked);
            }
        }
        if (!surface.readPixels(uploadPixels, 0, 0)) {
            throw new IllegalStateException("Unable to convert Skia GUI to straight-alpha RGBA");
        }
        // Keep a cheap raster-content diagnostic alongside the submitted frame
        // count. It detects an empty surface without scanning a whole 4K frame.
        var pixels = texture.getPixels().getPixelBytes();
        long visible = 0;
        for (int y = 0; y < height; y += 8) {
            for (int x = 0; x < width; x += 8) {
                if ((pixels.get((y * width + x) * 4 + 3) & 0xFF) != 0) {
                    visible++;
                }
            }
        }
        visiblePixelSamples = visible;
        texture.upload();
        extractor.blit(texture.getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                0, 0, extractor.guiWidth(), extractor.guiHeight(), 0.0F, 1.0F, 0.0F, 1.0F);
        frameSubmitted = true;
        if (FrameProfile.frame(profileStart, allocatedBefore)) {
            AtomChat.LOGGER.info(com.atom.chat.diagnostics.AtomChatLog.message("render", FrameProfile.summary()));
        }
    }

    static Canvas prepassCanvas() {
        return prepassCanvas;
    }

    public static long renderedFrames() {
        return renderedFrames;
    }

    /** Counts after vanilla has recorded draws for the extracted GUI. */
    public static void framePresented() {
        if (frameSubmitted) {
            renderedFrames++;
            frameSubmitted = false;
        }
    }

    public boolean isReady() {
        return surface != null && texture != null && !texture.getTexture().isClosed();
    }

    public long visiblePixelSamples() {
        return visiblePixelSamples;
    }

    public Surface requireSurface() {
        if (surface == null) {
            throw new IllegalStateException("Skia surface not initialized");
        }
        return surface;
    }

    public void releaseWorldSnapshot() {
        PanelBlurRenderer.releaseWorldSnapshot();
    }

    @Override
    public void close() {
        if (uploadPixels != null) {
            uploadPixels.close();
            uploadPixels = null;
        }
        if (surface != null) {
            surface.close();
            surface = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        width = height = 0;
        visiblePixelSamples = 0;
        PanelBlurRenderer.releaseWorldSnapshot();
    }
}
