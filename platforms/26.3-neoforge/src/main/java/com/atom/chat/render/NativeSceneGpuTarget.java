package com.atom.chat.render;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ContentChangeMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceColorFormat;
import io.github.humbleui.skija.SurfaceOrigin;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;

import java.util.Objects;

/** Dedicated external scene FBO; its stencil is never reused by Humble drawing. */
public final class NativeSceneGpuTarget implements AutoCloseable {
    private final SharedGlSession session;
    private final int width;
    private final int height;
    private final Runnable releaseProducer;
    private BackendRenderTarget target;
    private Surface surface;
    private int framebuffer;
    private int stencil;
    private int texture;
    private boolean closed;

    public NativeSceneGpuTarget(SharedGlSession session, int width, int height, Runnable releaseProducer) {
        this.session = Objects.requireNonNull(session);
        this.releaseProducer = Objects.requireNonNull(releaseProducer);
        if (width <= 0 || height <= 0 || !session.isOpen()) {
            throw new IllegalArgumentException("A native scene target requires an open session and positive dimensions");
        }
        this.width = width;
        this.height = height;
        try {
            session.inContext(this::allocate);
            session.attach(this);
        } catch (RuntimeException | LinkageError failure) {
            HiddenGlContext.Cleanup cleanup = new HiddenGlContext.Cleanup(failure);
            cleanup.run(this::close);
            cleanup.rethrow();
            throw failure;
        }
    }

    public int framebuffer() {
        requireOpen();
        return framebuffer;
    }

    public long contextToken() {
        return session.token();
    }

    public boolean validFor(SharedGlSession candidate, int width, int height) {
        return !closed && session.isOpen() && session == candidate && this.width == width && this.height == height;
    }

    /** Call immediately before producer rendering in this session's current GL context. */
    public void beforeProducerDraw() {
        requireOpen();
        session.beforeExternalDraw();
        // Wrapped render targets snapshot by copying on the GPU. Submit any
        // previous copy before the producer overwrites its source attachment.
        surface.flushAndSubmit();
        surface.notifyContentWillChange(ContentChangeMode.DISCARD);
    }

    /** Producer must flush its own context before this GPU-only snapshot. */
    public Image snapshot() {
        requireOpen();
        session.afterExternalDraw();
        return surface.makeImageSnapshot();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        session.inContext(() -> {
            // Mark before callback: producer disposal can reenter target disposal.
            closed = true;
            session.detach(this);
            HiddenGlContext.Cleanup cleanup = new HiddenGlContext.Cleanup(null);
            cleanup.run(session::beforeExternalDraw);
            if (surface != null) {
                cleanup.run(surface::flushAndSubmit);
            }
            cleanup.run(releaseProducer);
            cleanup.run(session::afterExternalDraw);
            cleanup.run(this::closeResources);
            cleanup.rethrow();
        });
    }

    private void allocate() {
        session.beforeExternalDraw();
        try {
            // Pixel unpack buffers are caller-owned and must not interpret 0 as
            // an offset into an unrelated upload when allocating texture storage.
            GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            texture = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height, 0,
                    GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            framebuffer = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, texture, 0);
            stencil = GL30C.glGenRenderbuffers();
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, stencil);
            GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_STENCIL_INDEX8, width, height);
            GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT,
                    GL30C.GL_RENDERBUFFER, stencil);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete AtomChat native scene framebuffer");
            }
            target = BackendRenderTarget.makeGL(width, height, 0, 8, framebuffer, GL11C.GL_RGBA8);
            surface = Surface.wrapBackendRenderTarget(session.humbleContext(), target,
                    SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.getSRGB());
            if (surface == null) {
                throw new IllegalStateException("Unable to wrap AtomChat native scene framebuffer");
            }
        } finally {
            session.afterExternalDraw();
        }
    }

    private void requireOpen() {
        if (closed || !session.isOpen()) {
            throw new IllegalStateException("Native scene GPU target is closed");
        }
    }

    private void closeResources() {
        Surface oldSurface = surface;
        BackendRenderTarget oldTarget = target;
        int oldFramebuffer = framebuffer;
        int oldStencil = stencil;
        int oldTexture = texture;
        surface = null;
        target = null;
        framebuffer = stencil = texture = 0;
        HiddenGlContext.Cleanup cleanup = new HiddenGlContext.Cleanup(null);
        if (oldSurface != null) {
            cleanup.run(oldSurface::close);
        }
        if (oldTarget != null) {
            cleanup.run(oldTarget::close);
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
