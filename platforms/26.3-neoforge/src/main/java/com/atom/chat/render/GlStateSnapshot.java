package com.atom.chat.render;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Restores driver state around Ganesh without touching RenderPearl's caches. */
final class GlStateSnapshot implements AutoCloseable {
    private static final int GL12_IMAGE_HEIGHT = 0x806E;
    private static final int GL12_SKIP_IMAGES = 0x806D;
    private static final int[] CAPABILITIES = {
            GL11C.GL_DEPTH_TEST, GL11C.GL_CULL_FACE, GL11C.GL_SCISSOR_TEST,
            GL11C.GL_STENCIL_TEST, GL11C.GL_DITHER, GL11C.GL_POLYGON_OFFSET_FILL,
            GL13C.GL_MULTISAMPLE, GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE,
            GL13C.GL_SAMPLE_COVERAGE, GL30C.GL_FRAMEBUFFER_SRGB,
            GL30C.GL_RASTERIZER_DISCARD, GL11C.GL_COLOR_LOGIC_OP
    };
    private static final int[] BUFFER_TARGETS = {
            GL15C.GL_ARRAY_BUFFER, GL21C.GL_PIXEL_PACK_BUFFER, GL21C.GL_PIXEL_UNPACK_BUFFER,
            GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, GL31C.GL_UNIFORM_BUFFER
    };
    private static final int[] BUFFER_QUERIES = {
            GL15C.GL_ARRAY_BUFFER_BINDING, GL21C.GL_PIXEL_PACK_BUFFER_BINDING,
            GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING, GL31C.GL_COPY_READ_BUFFER,
            GL31C.GL_COPY_WRITE_BUFFER, GL31C.GL_UNIFORM_BUFFER_BINDING
    };
    private static final int[] PIXEL_STORE = {
            GL11C.GL_PACK_ALIGNMENT, GL11C.GL_PACK_ROW_LENGTH, GL11C.GL_PACK_SKIP_PIXELS,
            GL11C.GL_PACK_SKIP_ROWS, GL11C.GL_PACK_SWAP_BYTES, GL11C.GL_PACK_LSB_FIRST,
            GL11C.GL_UNPACK_ALIGNMENT, GL11C.GL_UNPACK_ROW_LENGTH, GL11C.GL_UNPACK_SKIP_PIXELS,
            GL11C.GL_UNPACK_SKIP_ROWS, GL11C.GL_UNPACK_SWAP_BYTES, GL11C.GL_UNPACK_LSB_FIRST,
            GL12_IMAGE_HEIGHT, GL12_SKIP_IMAGES
    };
    private final boolean[] capabilities = new boolean[CAPABILITIES.length];
    private final int[] buffers = new int[BUFFER_TARGETS.length];
    private final int[] pixelStore = new int[PIXEL_STORE.length];
    private final int[] viewport = new int[4];
    private final int[] scissor = new int[4];
    private final float[] blendColor = new float[4];
    private final int[] polygonMode = new int[2];
    private final int[] textureBindings;
    private final int[] samplers;
    private final boolean[] blends;
    private final boolean[][] colorMasks;
    private final int activeTexture;
    private final int readFbo;
    private final int drawFbo;
    private final int renderbuffer;
    private final int program;
    private final int vao;
    private final int elementBuffer;
    private final int depthFunc;
    private final boolean depthMask;
    private final int cullFace;
    private final int frontFace;
    private final int srcRgb;
    private final int dstRgb;
    private final int srcAlpha;
    private final int dstAlpha;
    private final int equationRgb;
    private final int equationAlpha;
    private final int[] stencilFront;
    private final int[] stencilBack;
    private boolean closed;

    static GlStateSnapshot capture() {
        return new GlStateSnapshot();
    }

    private GlStateSnapshot() {
        for (int i = 0; i < capabilities.length; i++) {
            capabilities[i] = GL11C.glIsEnabled(CAPABILITIES[i]);
        }
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = GL11C.glGetInteger(BUFFER_QUERIES[i]);
        }
        for (int i = 0; i < pixelStore.length; i++) {
            pixelStore[i] = GL11C.glGetInteger(PIXEL_STORE[i]);
        }
        activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int units = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        textureBindings = new int[units];
        samplers = new int[units];
        for (int i = 0; i < units; i++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
            textureBindings[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            samplers[i] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        }
        GL13C.glActiveTexture(activeTexture);
        int targets = GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);
        blends = new boolean[targets];
        colorMasks = new boolean[targets][4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fourInts = stack.mallocInt(4);
            FloatBuffer fourFloats = stack.mallocFloat(4);
            ByteBuffer fourBytes = stack.malloc(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, fourInts);
            fourInts.get(0, viewport);
            GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, fourInts);
            fourInts.get(0, scissor);
            GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE, fourInts);
            polygonMode[0] = fourInts.get(0);
            polygonMode[1] = fourInts.get(1);
            GL11C.glGetFloatv(0x8005, fourFloats); // GL_BLEND_COLOR
            fourFloats.get(0, blendColor);
            for (int i = 0; i < targets; i++) {
                blends[i] = GL30C.glIsEnabledi(GL11C.GL_BLEND, i);
                GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, i, fourBytes);
                for (int c = 0; c < 4; c++) {
                    colorMasks[i][c] = fourBytes.get(c) != 0;
                }
            }
        }
        readFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        drawFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        renderbuffer = GL11C.glGetInteger(GL30C.GL_RENDERBUFFER_BINDING);
        program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        vao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        elementBuffer = GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        cullFace = GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE);
        frontFace = GL11C.glGetInteger(GL11C.GL_FRONT_FACE);
        srcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        dstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        srcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        dstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        equationRgb = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB);
        equationAlpha = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA);
        stencilFront = captureStencil(false);
        stencilBack = captureStencil(true);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (int i = 0; i < capabilities.length; i++) {
            if (capabilities[i]) {
                GL11C.glEnable(CAPABILITIES[i]);
            } else {
                GL11C.glDisable(CAPABILITIES[i]);
            }
        }
        GL14C.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL20C.glBlendEquationSeparate(equationRgb, equationAlpha);
        GL14C.glBlendColor(blendColor[0], blendColor[1], blendColor[2], blendColor[3]);
        for (int i = 0; i < blends.length; i++) {
            if (blends[i]) {
                GL30C.glEnablei(GL11C.GL_BLEND, i);
            } else {
                GL30C.glDisablei(GL11C.GL_BLEND, i);
            }
            boolean[] mask = colorMasks[i];
            GL30C.glColorMaski(i, mask[0], mask[1], mask[2], mask[3]);
        }
        GL11C.glDepthFunc(depthFunc);
        GL11C.glDepthMask(depthMask);
        GL11C.glCullFace(cullFace);
        GL11C.glFrontFace(frontFace);
        // Core OpenGL accepts only FRONT_AND_BACK, whose queried modes match.
        GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, polygonMode[0]);
        restoreStencil(GL11C.GL_FRONT, stencilFront);
        restoreStencil(GL11C.GL_BACK, stencilBack);
        for (int i = 0; i < textureBindings.length; i++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textureBindings[i]);
            GL33C.glBindSampler(i, samplers[i]);
        }
        GL13C.glActiveTexture(activeTexture);
        GL20C.glUseProgram(program);
        GL30C.glBindVertexArray(vao);
        // Binding ELEMENT_ARRAY_BUFFER with VAO 0 is invalid in core profiles.
        if (vao != 0) {
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        }
        for (int i = 0; i < buffers.length; i++) {
            GL15C.glBindBuffer(BUFFER_TARGETS[i], buffers[i]);
        }
        for (int i = 0; i < pixelStore.length; i++) {
            GL11C.glPixelStorei(PIXEL_STORE[i], pixelStore[i]);
        }
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, renderbuffer);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFbo);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11C.glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
    }

    private static int[] captureStencil(boolean back) {
        return new int[]{
                GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_FUNC : GL11C.GL_STENCIL_FUNC),
                GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_REF : GL11C.GL_STENCIL_REF),
                GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_VALUE_MASK : GL11C.GL_STENCIL_VALUE_MASK),
                GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_WRITEMASK : GL11C.GL_STENCIL_WRITEMASK),
                GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_FAIL : GL11C.GL_STENCIL_FAIL),
                GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_PASS_DEPTH_FAIL : GL11C.GL_STENCIL_PASS_DEPTH_FAIL),
                GL11C.glGetInteger(back ? GL20C.GL_STENCIL_BACK_PASS_DEPTH_PASS : GL11C.GL_STENCIL_PASS_DEPTH_PASS)
        };
    }

    private static void restoreStencil(int face, int[] values) {
        GL20C.glStencilFuncSeparate(face, values[0], values[1], values[2]);
        GL20C.glStencilMaskSeparate(face, values[3]);
        GL20C.glStencilOpSeparate(face, values[4], values[5], values[6]);
    }
}
