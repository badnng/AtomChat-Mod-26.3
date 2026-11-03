package com.atom.chat.screen;

import com.atom.chat.image.ImageBounds;
import com.atom.chat.miuix.api.MiuixHost;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Image editing data for the Miuix modal; no legacy overlay rendering is used. */
public final class NativeMiuixCropper implements AutoCloseable {
    private static final int MAX_PREVIEW_SIDE = 1600;
    private static final int MAX_OUTPUT_SIDE = 1024;
    private static final int AVATAR_SIDE = 256;
    private final AtomicInteger generation = new AtomicInteger();
    private Image source;
    private byte[] preview;
    private boolean active;
    private boolean circle;
    private float aspect = 1;
    private float zoom = 1;
    private float offsetX;
    private float offsetY;
    private String key = "";
    private String error;
    private Consumer<byte[]> onComplete;

    /** Decode off-thread, then publish/dispose raster handles on the game thread. */
    public void open(Path file, boolean circular, float ratio, String target, Consumer<byte[]> callback) {
        close();
        int request = generation.incrementAndGet();
        active = true;
        circle = circular;
        aspect = circular || !Float.isFinite(ratio) || ratio <= 0 ? 1 : ratio;
        zoom = 1;
        offsetX = 0;
        offsetY = 0;
        key = "crop:" + target + ":" + request;
        error = null;
        onComplete = callback;
        Thread worker = new Thread(() -> {
            Image decoded = null;
            byte[] png = null;
            String failure = null;
            try {
                if (file == null || !Files.isRegularFile(file)) throw new IOException("missing image");
                ImageBounds.Inspection inspected = ImageBounds.inspect(file);
                if (inspected.rejection() != null) throw new IOException(inspected.rejection());
                byte[] bytes = Files.readAllBytes(file);
                if (ImageBounds.rejection(bytes) != null) throw new IOException("image rejected");
                decoded = previewImage(Image.makeFromEncoded(bytes));
                try (Data data = decoded.encodeToData(EncodedImageFormat.PNG)) {
                    if (data == null) throw new IOException("could not encode preview");
                    png = data.getBytes();
                }
            } catch (RuntimeException | IOException failureCause) {
                if (decoded != null) decoded.close();
                decoded = null;
                failure = failureCause.getMessage();
            }
            Image result = decoded;
            byte[] encoded = png;
            String rejected = failure;
            Minecraft.getInstance().execute(() -> {
                if (request != generation.get() || !active) {
                    if (result != null) result.close();
                    return;
                }
                source = result;
                preview = encoded;
                if (result == null) {
                    error = rejected == null ? "image decode failed" : rejected;
                    active = false;
                }
            });
        }, "AtomChat-MiuixCropDecode");
        worker.setDaemon(true);
        worker.start();
    }

    public MiuixHost.CropEditor editor() {
        return active ? new MiuixHost.CropEditor(key, source == null ? 0 : source.getWidth(),
                source == null ? 0 : source.getHeight(), circle, aspect, zoom, offsetX, offsetY) : null;
    }

    public byte[] sourcePng() {
        return preview;
    }

    /** Offsets choose crop-center travel: -1 is left/top, +1 is right/bottom. */
    public void setTransform(float newZoom, float x, float y) {
        zoom = finiteClamp(newZoom, 1, 8);
        offsetX = finiteClamp(x, -1, 1);
        offsetY = finiteClamp(y, -1, 1);
    }

    public void confirm() {
        if (!active || source == null) return;
        float[] region = sourceRegion(source.getWidth(), source.getHeight(), aspect, zoom, offsetX, offsetY);
        float outputScale = Math.min(1, MAX_OUTPUT_SIDE / Math.max(region[2], region[3]));
        int width = circle ? AVATAR_SIDE : Math.max(1, Math.round(region[2] * outputScale));
        int height = circle ? AVATAR_SIDE : Math.max(1, Math.round(region[3] * outputScale));
        byte[] png;
        try (Surface output = Surface.makeRasterN32Premul(width, height);
             Paint paint = new Paint().setAntiAlias(true)) {
            output.getCanvas().clear(0);
            output.getCanvas().drawImageRect(source, Rect.makeXYWH(region[0], region[1], region[2], region[3]),
                    Rect.makeWH(width, height), SamplingMode.LINEAR, paint, false);
            // Store a square avatar; the actual avatar component owns its circular mask.
            try (Image image = output.makeImageSnapshot(); Data data = image.encodeToData(EncodedImageFormat.PNG)) {
                if (data == null) throw new IllegalStateException("Could not encode cropped image");
                png = data.getBytes();
            }
        }
        Consumer<byte[]> completion = onComplete;
        close();
        if (completion != null) completion.accept(png);
    }

    public void cancel() {
        close();
    }

    public String takeError() {
        String result = error;
        error = null;
        return result;
    }

    /** Same source rectangle used by the Compose preview and PNG export. */
    static float[] sourceRegion(int width, int height, float aspect, float zoom, float x, float y) {
        float cropWidth = Math.min(width, height * aspect) / zoom;
        float cropHeight = cropWidth / aspect;
        return new float[]{(width - cropWidth) * (x + 1) / 2,
                (height - cropHeight) * (y + 1) / 2, cropWidth, cropHeight};
    }

    private static float finiteClamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    private static Image previewImage(Image image) {
        int side = Math.max(image.getWidth(), image.getHeight());
        if (side <= MAX_PREVIEW_SIDE) return image;
        float factor = (float) MAX_PREVIEW_SIDE / side;
        int width = Math.max(1, Math.round(image.getWidth() * factor));
        int height = Math.max(1, Math.round(image.getHeight() * factor));
        try (Image original = image; Surface output = Surface.makeRasterN32Premul(width, height);
             Paint paint = new Paint().setAntiAlias(true)) {
            output.getCanvas().drawImageRect(original, Rect.makeWH(original.getWidth(), original.getHeight()),
                    Rect.makeWH(width, height), SamplingMode.LINEAR, paint, false);
            return output.makeImageSnapshot();
        }
    }

    @Override
    public void close() {
        generation.incrementAndGet();
        active = false;
        preview = null;
        onComplete = null;
        if (source != null) {
            source.close();
            source = null;
        }
    }
}
