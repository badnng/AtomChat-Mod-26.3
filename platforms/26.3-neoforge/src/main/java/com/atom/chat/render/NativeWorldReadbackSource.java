package com.atom.chat.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

import java.util.function.Consumer;

/** Compatibility bridge: one asynchronous panel region, one reusable staging buffer. */
final class NativeWorldReadbackSource implements AutoCloseable {
    record Pixels(NativeWorldRegion region, byte[] rgba, boolean changed) { }
    private GpuBuffer buffer;
    private boolean pending;
    private boolean closed;
    private byte[] rgba;
    private byte[] rowScratch;
    private NativeWorldRegion previousRegion;

    boolean capture(RenderTarget target, NativeWorldRegion region, Consumer<Pixels> result,
                    Consumer<Throwable> failure) {
        if (closed || pending || region == null) return false;
        if (target.getColorTexture() == null || target.getColorTexture().getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalStateException("Native world readback requires RGBA8 world pixels");
        }
        int byteCount = region.byteCount();
        if (buffer == null || buffer.size() < byteCount) {
            if (buffer != null) buffer.close();
            buffer = RenderSystem.getDevice().createBuffer(() -> "AtomChat native world staging",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, byteCount);
        }
        GpuBuffer staging = buffer;
        pending = true;
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target.getColorTexture(), staging, 0, () -> {
                try {
                    if (closed) return;
                    boolean changed = !region.equals(previousRegion);
                    if (rgba == null || rgba.length != byteCount) {
                        rgba = new byte[byteCount];
                        changed = true;
                    }
                    try (var mapped = staging.map(0, byteCount, true, false)) {
                        var bytes = mapped.data();
                        int stride = region.width() * 4;
                        if (rowScratch == null || rowScratch.length < stride) rowScratch = new byte[stride];
                        changed = NativeWorldPixelCopy.copyTopDownOpaque(bytes, region.width(), region.height(),
                                rgba, rowScratch, changed);
                    }
                    previousRegion = region;
                    result.accept(new Pixels(region, rgba, changed));
                } catch (Throwable error) {
                    failure.accept(error);
                } finally {
                    pending = false;
                    if (closed) staging.close();
                }
            }, 0, region.x(), region.sourceY(target.height), region.width(), region.height());
        } catch (RuntimeException | LinkageError error) {
            pending = false;
            throw error;
        }
        return true;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (!pending && buffer != null) buffer.close();
        buffer = null;
        rgba = null;
        rowScratch = null;
    }
}
