package com.atom.chat.render;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;

/** Retained world attachment. Only its numeric FBO crosses into private Skiko. */
public final class NativeWorldGpuSource implements AutoCloseable {
    private final int width;
    private final int height;
    private final long nativeContext = SDLVideo.SDL_GL_GetCurrentContext();
    private int references = 1;
    private int sourceFramebuffer;
    private int framebuffer;
    private int texture;
    private long sequence;

    NativeWorldGpuSource(int width, int height) {
        this.width = width;
        this.height = height;
        if (nativeContext == 0) throw new IllegalStateException("World source requires the game GL context");
        try (GlStateSnapshot ignored = GlStateSnapshot.capture()) {
            sourceFramebuffer = GL30C.glGenFramebuffers();
            texture = allocateTexture(width, height);
            framebuffer = attach(texture);
        } catch (RuntimeException | LinkageError failure) {
            close();
            throw failure;
        }
    }

    public int width() { return width; }
    public int height() { return height; }
    public int framebuffer() { return framebuffer; }
    public long nativeContext() { return nativeContext; }
    public long sequence() { return sequence; }

    public NativeWorldGpuSource retain() {
        requireCurrent();
        if (references == 0) throw new IllegalStateException("World source has been released");
        references++;
        return this;
    }

    /** One queued GL stream orders world copies after the preceding Compose submit. */
    boolean capture(GlTexture source) {
        requireCurrent();
        try (CopyState ignored = CopyState.capture()) {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, source.glId(), 0);
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            // Blits respect scissor; the caller's world viewport may not cover our attachments.
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
            // Copy every scheduled slot. A reduced change probe cannot resolve
            // fine world motion and would silently lower the backdrop refresh rate.
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                    GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
            // The world is opaque even when the main target leaves alpha unset.
            // Keep the RGBA8 format supported by both independent Skia bindings.
            GL30C.glColorMaski(0, false, false, false, true);
            GL11C.glClearColor(0, 0, 0, 1);
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            sequence++;
            return true;
        }
    }

    @Override public void close() {
        if (references == 0) return;
        requireCurrent();
        if (--references != 0) return;
        try (GlStateSnapshot ignored = GlStateSnapshot.capture()) {
            if (framebuffer != 0) GL30C.glDeleteFramebuffers(framebuffer);
            if (sourceFramebuffer != 0) GL30C.glDeleteFramebuffers(sourceFramebuffer);
            if (texture != 0) GL11C.glDeleteTextures(texture);
            framebuffer = sourceFramebuffer = texture = 0;
        }
    }

    private void requireCurrent() {
        if (SDLVideo.SDL_GL_GetCurrentContext() != nativeContext) {
            throw new IllegalStateException("World source must be used in the game GL context");
        }
    }

    private static int allocateTexture(int width, int height) {
        GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        int texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height, 0,
                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        return texture;
    }

    private static int attach(int texture) {
        int framebuffer = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, texture, 0);
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            GL30C.glDeleteFramebuffers(framebuffer);
            throw new IllegalStateException("Incomplete native world framebuffer");
        }
        return framebuffer;
    }

    /** Blits touch no shaders/textures/VAOs; avoid a full Ganesh state scan at 60 Hz. */
    private record CopyState(int readFramebuffer, int drawFramebuffer, int packBuffer, int alignment,
                             int rowLength, int skipPixels, int skipRows, int swapBytes, int lsbFirst,
                             boolean scissor, boolean srgb, float clearRed, float clearGreen,
                             float clearBlue, float clearAlpha, boolean maskRed, boolean maskGreen,
                             boolean maskBlue, boolean maskAlpha) implements AutoCloseable {
        static CopyState capture() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var clearColor = stack.mallocFloat(4);
                var colorMask = stack.malloc(4);
                GL11C.glGetFloatv(GL11C.GL_COLOR_CLEAR_VALUE, clearColor);
                GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, 0, colorMask);
                return new CopyState(GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING),
                    GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT), GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH),
                    GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS), GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS),
                    GL11C.glGetInteger(GL11C.GL_PACK_SWAP_BYTES), GL11C.glGetInteger(GL11C.GL_PACK_LSB_FIRST),
                    GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST), GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB),
                    clearColor.get(0), clearColor.get(1), clearColor.get(2), clearColor.get(3),
                    colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
            }
        }

        @Override public void close() {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, packBuffer);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, alignment);
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, rowLength);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, skipPixels);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, skipRows);
            GL11C.glPixelStorei(GL11C.GL_PACK_SWAP_BYTES, swapBytes);
            GL11C.glPixelStorei(GL11C.GL_PACK_LSB_FIRST, lsbFirst);
            GL11C.glClearColor(clearRed, clearGreen, clearBlue, clearAlpha);
            GL30C.glColorMaski(0, maskRed, maskGreen, maskBlue, maskAlpha);
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST); else GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            if (srgb) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB); else GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
        }
    }
}
