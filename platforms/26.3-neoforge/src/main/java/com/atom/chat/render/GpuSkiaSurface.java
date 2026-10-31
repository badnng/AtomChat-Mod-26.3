package com.atom.chat.render;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceColorFormat;
import io.github.humbleui.skija.SurfaceOrigin;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;

/** Ganesh target with direct OpenGL sampling or readback for Vulkan uploads. */
final class GpuSkiaSurface implements AutoCloseable {
    private DirectContext context;
    private BackendRenderTarget target;
    private Surface surface;
    private int framebuffer;
    private int stencil;
    private GlStateSnapshot drawingState;
    private HiddenGlContext hiddenContext;
    private HiddenGlContext.Scope drawingContext;
    private DynamicTexture uploadTexture;
    private int ownedTexture;
    private int width;
    private int height;

    /** Uses a private GL context for Vulkan; unsupported backends keep raster fallback. */
    static GpuSkiaSurface create(DynamicTexture texture, int width, int height) {
        GlTexture glTexture = texture.getTexture() instanceof GlTexture value ? value : null;
        boolean vulkan = RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(java.util.Locale.ROOT).contains("vulkan");
        if (glTexture == null && !vulkan) {
            return null;
        }
        GpuSkiaSurface result = new GpuSkiaSurface();
        result.width = width;
        result.height = height;
        if (glTexture == null) {
            result.hiddenContext = HiddenGlContext.create();
            result.uploadTexture = texture;
        }
        try (HiddenGlContext.Scope hidden = result.hiddenContext == null ? null : result.hiddenContext.activate();
             GlStateSnapshot ignored = result.hiddenContext == null ? GlStateSnapshot.capture() : null) {
            result.context = DirectContext.makeGL();
            int textureId;
            if (glTexture != null) {
                textureId = glTexture.glId();
            } else {
                result.ownedTexture = GL11C.glGenTextures();
                textureId = result.ownedTexture;
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textureId);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height, 0,
                        GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            }
            result.framebuffer = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, result.framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, textureId, 0);
            result.stencil = GL30C.glGenRenderbuffers();
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, result.stencil);
            GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_STENCIL_INDEX8, width, height);
            GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT,
                    GL30C.GL_RENDERBUFFER, result.stencil);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete AtomChat Skia framebuffer");
            }
            result.target = BackendRenderTarget.makeGL(width, height, 0, 8, result.framebuffer, GL11C.GL_RGBA8);
            result.surface = Surface.wrapBackendRenderTarget(result.context, result.target,
                    SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.getSRGB());
            if (result.surface == null) {
                throw new IllegalStateException("Unable to wrap AtomChat Skia framebuffer");
            }
            return result;
        } catch (RuntimeException | LinkageError failure) {
            // This also handles a successful allocation followed by a failed
            // context/state restore. close() reactivates our context first.
            HiddenGlContext.Cleanup cleanup = new HiddenGlContext.Cleanup(failure);
            cleanup.run(result::close);
            cleanup.rethrow();
            throw failure;
        }
    }

    boolean needsUpload() {
        return uploadTexture != null;
    }

    Surface surface() {
        return surface;
    }

    void begin() {
        if (drawingState != null || drawingContext != null || surface == null) {
            throw new IllegalStateException("Skia GPU surface is closed or already drawing");
        }
        if (hiddenContext == null) {
            drawingState = GlStateSnapshot.capture();
        } else {
            drawingContext = hiddenContext.activate();
        }
        try {
            context.resetGLAll();
            GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        } catch (RuntimeException | LinkageError failure) {
            HiddenGlContext.Cleanup cleanup = new HiddenGlContext.Cleanup(failure);
            cleanup.run(this::restoreDrawingState);
            cleanup.rethrow();
            throw failure;
        }
    }

    void end() {
        if (drawingState == null && drawingContext == null) {
            throw new IllegalStateException("Skia GPU surface is not drawing");
        }
        try {
            surface.flushAndSubmit();
            if (uploadTexture != null) {
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer);
                GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
                GL21C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
                GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 4);
                GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
                GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
                GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
                // Keep bottom-up premultiplied RGBA. The GUI blit flips V and
                // uses premultiplied blending, so no per-pixel CPU conversion.
                GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE,
                        uploadTexture.getPixels().getPointer());
            }
        } catch (RuntimeException | LinkageError failure) {
            HiddenGlContext.Cleanup cleanup = new HiddenGlContext.Cleanup(failure);
            cleanup.run(this::restoreDrawingState);
            cleanup.rethrow();
            throw failure;
        }
        restoreDrawingState();
    }

    @Override
    public void close() {
        HiddenGlContext.Cleanup cleanup = new HiddenGlContext.Cleanup(null);
        if (drawingState != null || drawingContext != null) {
            cleanup.run(this::end);
        }
        if (surface != null || context != null || target != null || framebuffer != 0 || stencil != 0 || ownedTexture != 0) {
            cleanup.run(() -> {
                try (HiddenGlContext.Scope hidden = hiddenContext == null ? null : hiddenContext.activate();
                     GlStateSnapshot ignored = hiddenContext == null ? GlStateSnapshot.capture() : null) {
                    closeResources();
                }
            });
        }
        HiddenGlContext hidden = hiddenContext;
        hiddenContext = null;
        if (hidden != null) {
            cleanup.run(hidden::close);
        }
        cleanup.rethrow();
    }

    private void restoreDrawingState() {
        GlStateSnapshot state = drawingState;
        HiddenGlContext.Scope scope = drawingContext;
        drawingState = null;
        drawingContext = null;
        try {
            if (state != null) {
                state.close();
            }
        } finally {
            if (scope != null) {
                scope.close();
            }
        }
    }

    private void closeResources() {
        Surface oldSurface = surface;
        BackendRenderTarget oldTarget = target;
        DirectContext oldContext = context;
        int oldFramebuffer = framebuffer;
        int oldStencil = stencil;
        int oldTexture = ownedTexture;
        surface = null;
        target = null;
        context = null;
        framebuffer = stencil = ownedTexture = 0;
        HiddenGlContext.Cleanup cleanup = new HiddenGlContext.Cleanup(null);
        if (oldSurface != null) {
            cleanup.run(oldSurface::close);
        }
        if (oldTarget != null) {
            cleanup.run(oldTarget::close);
        }
        if (oldContext != null) {
            cleanup.run(oldContext::close);
        }
        if (oldFramebuffer != 0) {
            cleanup.run(() -> GL30C.glDeleteFramebuffers(oldFramebuffer));
        }
        if (oldStencil != 0) {
            cleanup.run(() -> GL30C.glDeleteRenderbuffers(oldStencil));
        }
        if (oldTexture != 0) {
            cleanup.run(() -> GL11C.glDeleteTextures(oldTexture));
        }
        cleanup.rethrow();
    }
}
