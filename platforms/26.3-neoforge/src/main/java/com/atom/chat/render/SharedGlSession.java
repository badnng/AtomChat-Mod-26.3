package com.atom.chat.render;

import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.Surface;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The lifetime of one native GL context used by Humble and external producers.
 * Only numeric GL object names cross that boundary; Skia objects never do.
 */
public final class SharedGlSession implements AutoCloseable {
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1);
    private final long token = NEXT_TOKEN.getAndIncrement();
    private final Thread ownerThread = Thread.currentThread();
    private final long window = SDLVideo.SDL_GL_GetCurrentWindow();
    private final long nativeContext = SDLVideo.SDL_GL_GetCurrentContext();
    private final GLCapabilities capabilities = GL.getCapabilities();
    private final DirectContext context;
    private final Surface ownerSurface;
    private final BooleanSupplier ownerDrawing;
    private final ArrayList<AutoCloseable> attachments = new ArrayList<>();
    private int scopeDepth;
    private boolean closing;
    private boolean closed;

    SharedGlSession(DirectContext context, Surface ownerSurface, BooleanSupplier ownerDrawing) {
        if (nativeContext == 0) {
            throw new IllegalStateException("A shared GL session requires a current native context");
        }
        this.context = Objects.requireNonNull(context);
        this.ownerSurface = Objects.requireNonNull(ownerSurface);
        this.ownerDrawing = Objects.requireNonNull(ownerDrawing);
    }

    public long token() {
        return token;
    }

    public boolean isOpen() {
        return !closing && !closed;
    }

    public void attach(AutoCloseable resource) {
        checkThread();
        if (!isOpen()) {
            throw new IllegalStateException("Shared GL session is closing or closed");
        }
        Objects.requireNonNull(resource);
        for (AutoCloseable attached : attachments) {
            if (attached == resource) {
                return;
            }
        }
        attachments.add(resource);
    }

    public void detach(AutoCloseable resource) {
        checkThread();
        attachments.removeIf(attached -> attached == resource);
    }

    /** Runs cleanup in the owner context, restoring the caller's GL state. */
    public void inContext(Runnable action) {
        checkThread();
        if (closed) {
            throw new IllegalStateException("Shared GL session is closed");
        }
        Objects.requireNonNull(action);
        if ((scopeDepth != 0 || ownerDrawing.getAsBoolean()) && isCurrent()) {
            runScoped(action);
            return;
        }
        long previousWindow = SDLVideo.SDL_GL_GetCurrentWindow();
        long previousContext = SDLVideo.SDL_GL_GetCurrentContext();
        GLCapabilities previousCapabilities = currentCapabilities();
        boolean switched = previousContext != nativeContext;
        if (switched && !SDLVideo.SDL_GL_MakeCurrent(window, nativeContext)) {
            throw contextFailure("activate");
        }
        try {
            GL.setCapabilities(capabilities);
            try (GlStateSnapshot ignored = GlStateSnapshot.capture()) {
                runScoped(action);
            }
        } finally {
            if (switched) {
                boolean restored = SDLVideo.SDL_GL_MakeCurrent(previousWindow, previousContext);
                GL.setCapabilities(previousCapabilities);
                if (!restored) {
                    throw contextFailure("restore");
                }
            } else {
                GL.setCapabilities(previousCapabilities);
            }
        }
    }

    /** Flushes commands that may still sample a producer's previous contents. */
    public void beforeExternalDraw() {
        checkCurrent();
        ownerSurface.flushAndSubmit();
    }

    /** Invalidates Humble's cached GL state after an external Skia context ran. */
    public void afterExternalDraw() {
        checkCurrent();
        context.resetGLAll();
    }

    /** The returned binding belongs exclusively to Humble and this session. */
    public DirectContext humbleContext() {
        checkCurrent();
        return context;
    }

    @Override
    public void close() {
        checkThread();
        if (closing || closed) {
            return;
        }
        closing = true;
        HiddenGlContext.Cleanup cleanup = new HiddenGlContext.Cleanup(null);
        try {
            cleanup.run(() -> inContext(() -> {
                cleanup.run(this::beforeExternalDraw);
                // Clear first so reentrant resource disposal cannot revisit the list.
                ArrayList<AutoCloseable> resources = new ArrayList<>(attachments);
                attachments.clear();
                for (int i = resources.size() - 1; i >= 0; i--) {
                    AutoCloseable resource = resources.get(i);
                    cleanup.run(() -> closeAttachment(resource));
                }
                cleanup.run(this::afterExternalDraw);
            }));
        } finally {
            attachments.clear();
            closed = true;
            closing = false;
        }
        cleanup.rethrow();
    }

    private void runScoped(Runnable action) {
        scopeDepth++;
        try {
            action.run();
        } finally {
            scopeDepth--;
        }
    }

    private boolean isCurrent() {
        return SDLVideo.SDL_GL_GetCurrentContext() == nativeContext;
    }

    private void checkCurrent() {
        checkThread();
        if (closed || !isCurrent()) {
            throw new IllegalStateException("Shared GL session must be used with its native context current");
        }
    }

    private void checkThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Shared GL session must be used on its owner render thread");
        }
    }

    private static GLCapabilities currentCapabilities() {
        try {
            return GL.getCapabilities();
        } catch (IllegalStateException unavailable) {
            return null;
        }
    }

    private static IllegalStateException contextFailure(String action) {
        return new IllegalStateException("Unable to " + action + " shared OpenGL context: " + SDLError.SDL_GetError());
    }

    private static void closeAttachment(AutoCloseable resource) {
        try {
            resource.close();
        } catch (RuntimeException | LinkageError failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to release shared GL resource", failure);
        }
    }
}
