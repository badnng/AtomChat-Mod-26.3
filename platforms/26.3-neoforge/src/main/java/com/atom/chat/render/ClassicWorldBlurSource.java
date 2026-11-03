package com.atom.chat.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.util.function.Consumer;

/** Classic Gaussian source with one reusable region readback and quarter-size raster. */
final class ClassicWorldBlurSource implements AutoCloseable {
    static final int DOWNSCALE = 4;
    // Three sigma covers the Gaussian kernel; rounding adds room for interpolation.
    static final int KERNEL_GUARD = 32;
    private final NativeWorldReadbackSource readback = new NativeWorldReadbackSource();
    private Surface blurred;
    private ImageFilter filter;
    private Paint paint;
    private ImageInfo info;
    private byte[] downsampled;
    private int width;
    private int height;
    private boolean pending;

    boolean pending() { return pending; }

    boolean capture(RenderTarget target, NativeWorldRegion region,
                    Consumer<NativeWorldReadbackSource.Pixels> result, Consumer<Throwable> failure) {
        if (pending) return false;
        pending = true;
        try {
            boolean submitted = readback.capture(target, region, pixels -> {
                pending = false;
                result.accept(pixels);
            }, error -> {
                pending = false;
                failure.accept(error);
            });
            if (!submitted) pending = false;
            return submitted;
        } catch (RuntimeException | LinkageError error) {
            pending = false;
            throw error;
        }
    }

    /** The previous output snapshot must be released before reusing the raster. */
    Image blur(NativeWorldReadbackSource.Pixels pixels) {
        NativeWorldRegion region = pixels.region();
        int nextWidth = (region.width() + DOWNSCALE - 1) / DOWNSCALE;
        int nextHeight = (region.height() + DOWNSCALE - 1) / DOWNSCALE;
        if (blurred == null || width != nextWidth || height != nextHeight) {
            if (blurred != null) blurred.close();
            width = nextWidth;
            height = nextHeight;
            info = new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE);
            downsampled = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
            blurred = Surface.makeRasterN32Premul(width, height);
        }
        if (filter == null) {
            filter = ImageFilter.makeBlur(9.0F / DOWNSCALE, 9.0F / DOWNSCALE, FilterTileMode.CLAMP);
            paint = new Paint().setImageFilter(filter);
        }
        downsample(pixels.rgba(), region.width(), region.height());
        try (Image world = Image.makeRasterFromBytes(info, downsampled, width * 4L)) {
            blurred.getCanvas().clear(0);
            blurred.getCanvas().drawImageRect(world, Rect.makeWH(width, height), Rect.makeWH(width, height),
                    SamplingMode.LINEAR, paint, false);
            return blurred.makeImageSnapshot();
        }
    }

    private void downsample(byte[] rgba, int sourceWidth, int sourceHeight) {
        int destination = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0;
                for (int dy = 0; dy < DOWNSCALE; dy++) {
                    int sourceRow = Math.min(y * DOWNSCALE + dy, sourceHeight - 1) * sourceWidth;
                    for (int dx = 0; dx < DOWNSCALE; dx++) {
                        int source = (sourceRow + Math.min(x * DOWNSCALE + dx, sourceWidth - 1)) * 4;
                        red += rgba[source] & 0xFF;
                        green += rgba[source + 1] & 0xFF;
                        blue += rgba[source + 2] & 0xFF;
                    }
                }
                downsampled[destination++] = (byte) (red / (DOWNSCALE * DOWNSCALE));
                downsampled[destination++] = (byte) (green / (DOWNSCALE * DOWNSCALE));
                downsampled[destination++] = (byte) (blue / (DOWNSCALE * DOWNSCALE));
                downsampled[destination++] = (byte) 255;
            }
        }
    }

    @Override public void close() {
        readback.close();
        if (paint != null) { paint.close(); paint = null; }
        if (filter != null) { filter.close(); filter = null; }
        if (blurred != null) { blurred.close(); blurred = null; }
        downsampled = null;
        info = null;
    }
}
