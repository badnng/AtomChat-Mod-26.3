package com.atom.chat.render;

import com.atom.chat.AtomChat;
import com.atom.chat.diagnostics.FrameProfile;
import com.atom.chat.mixin.GuiGraphicsExtractorBlitInvoker;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Pixmap;
import io.github.humbleui.skija.Surface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.function.BiConsumer;

/**
 * Skia bridge for RenderPearl's extracted GUI. OpenGL draws into an offscreen
 * GPU texture. Vulkan uses an isolated Skia GL context and transfers only the
 * panel texture; a bounded raster upload remains the compatibility fallback.
 * Vanilla composes the textures with the matching alpha convention.
 */
public final class SkiaGraphics implements AutoCloseable {
    private static Canvas prepassCanvas;
    private static long renderedFrames;
    private static boolean frameSubmitted;
    private Surface surface;
    private GpuSkiaSurface gpuSurface;
    private boolean gpuFailed;
    private DynamicTexture texture;
    private Pixmap uploadPixels;
    private int width;
    private int height;
    private int viewportWidth;
    private int viewportHeight;
    private PanelRasterBounds bounds;
    private long visiblePixelSamples;

    public void checkFrameBufferId() {
        var window = Minecraft.getInstance().getWindow();
        // GameRenderer resizes its main target after extraction. Use the same
        // window dimensions it already extracted for the upcoming render pass.
        if (surface != null && (viewportWidth != Math.max(1, window.getWidth())
                || viewportHeight != Math.max(1, window.getHeight()))) {
            close();
        }
    }

    public void createSurface() {
        var window = Minecraft.getInstance().getWindow();
        createSurface(new PanelRasterBounds(0, 0, Math.max(1, window.getWidth()), Math.max(1, window.getHeight())));
    }

    private void createSurface(PanelRasterBounds requestedBounds) {
        closeRaster();
        var window = Minecraft.getInstance().getWindow();
        viewportWidth = Math.max(1, window.getWidth());
        viewportHeight = Math.max(1, window.getHeight());
        bounds = requestedBounds;
        width = bounds.width();
        height = bounds.height();
        texture = new DynamicTexture("AtomChat Skia GUI", width, height, false);
        if (!gpuFailed) {
            try {
                gpuSurface = GpuSkiaSurface.create(texture, width, height);
            } catch (RuntimeException | LinkageError error) {
                gpuFailed = true;
                AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("render",
                        "Skia GPU surface unavailable, using raster rendering"), error);
            }
        }
        surface = gpuSurface != null ? gpuSurface.surface()
                : Surface.makeRaster(new ImageInfo(width, height, ColorType.RGBA_8888,
                        ColorAlphaType.PREMUL, ColorSpace.getSRGB()));
        NativeImage pixels = texture.getPixels();
        uploadPixels = Pixmap.make(new ImageInfo(width, height, ColorType.RGBA_8888,
                ColorAlphaType.UNPREMUL, ColorSpace.getSRGB()), pixels.getPointer(), width * 4);
    }

    public void draw(GuiGraphicsExtractor extractor, Runnable preUi, float density, PanelRasterBounds requestedBounds,
                     BiConsumer<Canvas, Image> renderer) {
        RenderSystem.assertOnRenderThread();
        long profileStart = FrameProfile.start();
        long allocatedBefore = profileStart == 0L ? -1L : FrameProfile.allocatedBytes();
        checkFrameBufferId();
        if (surface == null || !requestedBounds.equals(bounds)) {
            createSurface(requestedBounds);
        }
        if (density <= 0.0F) {
            density = Math.max(1.0F, viewportHeight / 1080.0F);
        }
        Canvas canvas = surface.getCanvas();
        if (gpuSurface != null) {
            gpuSurface.begin();
        }
        try {
            canvas.clear(0);
            int baseline = canvas.getSaveCount();
            canvas.save();
            try {
                // Keep both the physical-pixel blur pre-pass and design-space UI
                // in their original screen coordinates on this smaller raster.
                canvas.translate(-bounds.x(), -bounds.y());
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
        } finally {
            if (gpuSurface != null) {
                gpuSurface.end();
            }
        }
        if (gpuSurface == null) {
            if (!surface.readPixels(uploadPixels, 0, 0)) {
                throw new IllegalStateException("Unable to convert Skia GUI to straight-alpha RGBA");
            }
            texture.upload();
        } else if (gpuSurface.needsUpload()) {
            texture.upload();
        }
        // Automation requests this diagnostic explicitly; normal frames need
        // neither an alpha scan nor Java accesses to the upload buffer.
        visiblePixelSamples = -1;
        extractor.pose().pushMatrix();
        try {
            // Scale physical coordinates exactly as the old full-window blit
            // did, including odd window sizes and fractional GUI scaling.
            extractor.pose().scale((float) extractor.guiWidth() / viewportWidth,
                    (float) extractor.guiHeight() / viewportHeight);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            if (gpuSurface != null) {
                ((GuiGraphicsExtractorBlitInvoker) extractor).atomchat$blit(
                        RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, texture.getTextureView(), sampler,
                        bounds.x(), bounds.y(), bounds.x() + width, bounds.y() + height,
                        0.0F, 1.0F, 1.0F, 0.0F, -1);
            } else {
                extractor.blit(texture.getTextureView(), sampler,
                        bounds.x(), bounds.y(), bounds.x() + width, bounds.y() + height,
                        0.0F, 1.0F, 0.0F, 1.0F);
            }
        } finally {
            extractor.pose().popMatrix();
        }
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

    public boolean isGpuAccelerated() {
        return gpuSurface != null;
    }

    public String rendererBackend() {
        return gpuSurface == null ? "skia-raster"
                : gpuSurface.needsUpload() ? "skia-opengl-to-vulkan" : "skia-opengl";
    }

    public long visiblePixelSamples() {
        if (visiblePixelSamples < 0 && texture != null) {
            if (gpuSurface != null) {
                gpuSurface.begin();
                try {
                    if (!surface.readPixels(uploadPixels, 0, 0)) {
                        return 0;
                    }
                } finally {
                    gpuSurface.end();
                }
            }
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
        }
        return visiblePixelSamples;
    }

    public Surface requireSurface() {
        if (surface == null) {
            throw new IllegalStateException("Skia surface not initialized");
        }
        return surface;
    }

    /** On-demand diagnostic readback, isolated from RenderPearl's GL state. */
    public byte[] capturePng() {
        if (gpuSurface != null) {
            gpuSurface.begin();
        }
        try {
            if (!requireSurface().readPixels(uploadPixels, 0, 0)) {
                throw new IllegalStateException("Unable to read Skia GUI snapshot");
            }
            // Encoding a GPU image without an explicit context is unsupported
            // by Skija. Copy only when a diagnostic snapshot is requested.
            try (Image snapshot = Image.makeRasterFromPixmap(uploadPixels);
                 var data = snapshot.encodeToData(EncodedImageFormat.PNG)) {
                if (data == null) {
                    throw new IllegalStateException("Unable to encode Skia GUI snapshot");
                }
                return data.getBytes();
            }
        } finally {
            if (gpuSurface != null) {
                gpuSurface.end();
            }
        }
    }

    public void releaseWorldSnapshot() {
        PanelBlurRenderer.releaseWorldSnapshot();
    }

    @Override
    public void close() {
        closeRaster();
        PanelBlurRenderer.releaseWorldSnapshot();
    }

    private void closeRaster() {
        if (uploadPixels != null) {
            uploadPixels.close();
            uploadPixels = null;
        }
        if (gpuSurface != null) {
            gpuSurface.close();
            gpuSurface = null;
            surface = null;
        } else if (surface != null) {
            surface.close();
            surface = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        width = height = 0;
        viewportWidth = viewportHeight = 0;
        bounds = null;
        visiblePixelSamples = 0;
    }
}
