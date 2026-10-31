package com.atom.chat.render;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/** A private, never-shown SDL context used only when Minecraft renders with Vulkan. */
final class HiddenGlContext implements AutoCloseable {
    private long window;
    private long context;
    private GLCapabilities capabilities;

    static HiddenGlContext create() {
        HiddenGlContext result = new HiddenGlContext();
        long previousWindow = SDLVideo.SDL_GL_GetCurrentWindow();
        long previousContext = SDLVideo.SDL_GL_GetCurrentContext();
        GLCapabilities previousCapabilities = currentCapabilities();
        int[] attributes = {SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION, SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION,
                SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK, SDLVideo.SDL_GL_SHARE_WITH_CURRENT_CONTEXT};
        // SDL defaults when no GL context exists to query. These affect only
        // subsequently created GL contexts, never the active Vulkan window.
        int[] previousAttributes = {2, 1, 0, 0};
        int[] requestedAttributes = {3, 3, SDLVideo.SDL_GL_CONTEXT_PROFILE_CORE, 0};
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.mallocInt(1);
            for (int i = 0; i < attributes.length; i++) {
                if (SDLVideo.SDL_GL_GetAttribute(attributes[i], value)) {
                    previousAttributes[i] = value.get(0);
                }
            }
        }
        Throwable creationFailure = null;
        try {
            for (int i = 0; i < attributes.length; i++) {
                if (!SDLVideo.SDL_GL_SetAttribute(attributes[i], requestedAttributes[i])) {
                    throw failure("configure hidden OpenGL context");
                }
            }
            result.window = SDLVideo.SDL_CreateWindow("AtomChat offscreen renderer", 16, 16,
                    SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_HIDDEN);
            if (result.window == 0) {
                throw failure("create hidden OpenGL window");
            }
            result.context = SDLVideo.SDL_GL_CreateContext(result.window);
            if (result.context == 0 || !SDLVideo.SDL_GL_MakeCurrent(result.window, result.context)) {
                throw failure("create hidden OpenGL context");
            }
            result.capabilities = GL.createCapabilities();
            if (!result.capabilities.OpenGL33) {
                throw new IllegalStateException("AtomChat offscreen renderer requires OpenGL 3.3");
            }
        } catch (RuntimeException | LinkageError failure) {
            creationFailure = failure;
        }
        Cleanup cleanup = new Cleanup(creationFailure);
        cleanup.run(() -> {
            for (int i = 0; i < attributes.length; i++) {
                SDLVideo.SDL_GL_SetAttribute(attributes[i], previousAttributes[i]);
            }
        });
        cleanup.run(() -> restore(previousWindow, previousContext, previousCapabilities));
        if (cleanup.hasFailure()) {
            cleanup.run(result::close);
        }
        cleanup.rethrow();
        return result;
    }

    Scope activate() {
        if (context == 0) {
            throw new IllegalStateException("Hidden OpenGL context is closed");
        }
        Scope scope = new Scope(SDLVideo.SDL_GL_GetCurrentWindow(), SDLVideo.SDL_GL_GetCurrentContext(),
                currentCapabilities());
        if (!SDLVideo.SDL_GL_MakeCurrent(window, context)) {
            throw failure("activate hidden OpenGL context");
        }
        try {
            GL.setCapabilities(capabilities);
        } catch (RuntimeException | LinkageError failure) {
            Cleanup cleanup = new Cleanup(failure);
            cleanup.run(scope::close);
            cleanup.rethrow();
            throw failure;
        }
        return scope;
    }

    @Override
    public void close() {
        long oldContext = context;
        long oldWindow = window;
        context = window = 0;
        Cleanup cleanup = new Cleanup(null);
        if (oldContext != 0) {
            if (SDLVideo.SDL_GL_GetCurrentContext() == oldContext) {
                cleanup.run(() -> restore(0, 0, null));
            }
            cleanup.run(() -> SDLVideo.SDL_GL_DestroyContext(oldContext));
        }
        if (oldWindow != 0) {
            cleanup.run(() -> SDLVideo.SDL_DestroyWindow(oldWindow));
        }
        cleanup.rethrow();
    }

    private static GLCapabilities currentCapabilities() {
        try {
            return GL.getCapabilities();
        } catch (IllegalStateException unavailable) {
            return null;
        }
    }

    private static void restore(long window, long context, GLCapabilities capabilities) {
        boolean restored = SDLVideo.SDL_GL_MakeCurrent(window, context);
        GL.setCapabilities(capabilities);
        if (!restored) {
            throw failure("restore previous OpenGL context");
        }
    }

    private static IllegalStateException failure(String action) {
        return new IllegalStateException("Unable to " + action + ": " + SDLError.SDL_GetError());
    }

    /** Keeps the first recoverable failure while attempting every cleanup. */
    static final class Cleanup {
        private Throwable failure;

        Cleanup(Throwable failure) {
            this.failure = failure;
        }

        void run(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException | LinkageError next) {
                if (failure == null) {
                    failure = next;
                } else if (next != failure) {
                    failure.addSuppressed(next);
                }
            }
        }

        boolean hasFailure() {
            return failure != null;
        }

        void rethrow() {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof LinkageError linkage) {
                throw linkage;
            }
        }
    }

    static final class Scope implements AutoCloseable {
        private final long window;
        private final long context;
        private final GLCapabilities capabilities;
        private boolean closed;

        private Scope(long window, long context, GLCapabilities capabilities) {
            this.window = window;
            this.context = context;
            this.capabilities = capabilities;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                restore(window, context, capabilities);
            }
        }
    }
}
