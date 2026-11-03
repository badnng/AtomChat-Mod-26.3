package com.atom.chat.render;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.ui.PanelBackground;
import com.atom.chat.ui.PanelOpenMotion;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Color;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Pixmap;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelBlurCompositeTest {
    private static final int VIEWPORT_WIDTH = 800;
    private static final int VIEWPORT_HEIGHT = 700;
    private static final float DENSITY = 1.25F;
    private static final float PANEL_X = 140.25F;
    private static final float PANEL_Y = 110.5F;
    private static final float PANEL_WIDTH = 300.25F;
    private static final float PANEL_HEIGHT = 360.5F;
    private static final float PANEL_RADIUS = 28.0F;
    private static final float TINT_OPACITY = 0.4F;
    private static final int TINT_COLOR = 0xFF536F91;
    private static final int SAMPLE_X = 344;
    private static final int SAMPLE_Y = 356;
    private static final int WORLD_CELL_SIZE = 12;
    private static final int MAX_ALPHA_ERROR = 1;
    private static final int MAX_PREMUL_CHANNEL_ERROR = 2;
    private static final int MAX_STRAIGHT_CHANNEL_ERROR = 2;
    private static final float[] PROGRESS_VALUES = {0.1F, 0.4F, 0.8F, 1.0F};

    @Test
    void onePanelLayerFadesCachedBlurAndTintTogetherWithoutMotionDrift() throws Exception {
        PanelRasterBounds crop = PanelRasterBounds.of(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, DENSITY,
                PANEL_X, PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT);
        assertTrue(crop.x() > 0 && crop.y() > 0, "exercise a crop with a non-zero physical origin");

        try (Image world = makeStripedGradientWorld()) {
            withInjectedWorld(world, () -> {
                for (AtomChatConfig.PageNavStyle style : new AtomChatConfig.PageNavStyle[]{
                        AtomChatConfig.PageNavStyle.SLIDE, AtomChatConfig.PageNavStyle.ZOOM}) {
                    for (float progress : PROGRESS_VALUES) {
                        Pixel sample = drawFrameAndRead(crop, style, progress);
                        int expectedAlpha = Math.round(progress * 255.0F);
                        assertNear(expectedAlpha, sample.alpha(), MAX_ALPHA_ERROR,
                                style + " progress " + progress + " should apply one unified layer fade");

                        int[] expectedRgb = expectedTintedWorldPixel(SAMPLE_X, SAMPLE_Y);
                        assertPremulNear(expectedRgb[0] * progress, sample.red(), sample.alpha(),
                                style + " progress " + progress + " red should retain the physical world sample");
                        assertPremulNear(expectedRgb[1] * progress, sample.green(), sample.alpha(),
                                style + " progress " + progress + " green should retain the physical world sample");
                        assertPremulNear(expectedRgb[2] * progress, sample.blue(), sample.alpha(),
                                style + " progress " + progress + " blue should retain the physical world sample");

                        // At low alpha, UNPREMUL readback quantizes the straight
                        // channels by roughly 255 / alpha. Keep the direct color
                        // assertion at the high-alpha frames where it is precise.
                        if (progress >= 0.8F) {
                            assertNear(expectedRgb[0], sample.red(), MAX_STRAIGHT_CHANNEL_ERROR,
                                    style + " high-alpha red must preserve the screen-fixed blur/tint ratio");
                            assertNear(expectedRgb[1], sample.green(), MAX_STRAIGHT_CHANNEL_ERROR,
                                    style + " high-alpha green must preserve the screen-fixed blur/tint ratio");
                            assertNear(expectedRgb[2], sample.blue(), MAX_STRAIGHT_CHANNEL_ERROR,
                                    style + " high-alpha blue must preserve the screen-fixed blur/tint ratio");
                        }
                    }
                }
            });
        }
    }

    @Test
    void missingCachedSnapshotReturnsFalseAndLeavesCanvasStateUntouched() throws Exception {
        PanelRasterBounds crop = PanelRasterBounds.of(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, DENSITY,
                PANEL_X, PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT);
        try (Surface tested = makeSmallSurface(); Surface control = makeSmallSurface()) {
            Canvas testedCanvas = tested.getCanvas();
            Canvas controlCanvas = control.getCanvas();
            prepareNoSnapshotCanvas(testedCanvas);
            prepareNoSnapshotCanvas(controlCanvas);
            int baseline = testedCanvas.getSaveCount();

            withInjectedWorld(null, () -> assertFalse(PanelBlurRenderer.render(testedCanvas, crop,
                    VIEWPORT_WIDTH, VIEWPORT_HEIGHT, PANEL_X, PANEL_Y,
                    PANEL_WIDTH, PANEL_HEIGHT, PANEL_RADIUS)));

            assertEquals(baseline, testedCanvas.getSaveCount(), "a cache miss must balance the save stack");
            drawNoSnapshotControlMark(testedCanvas);
            drawNoSnapshotControlMark(controlCanvas);
            assertArrayEquals(readPixels(tested, 64, 64), readPixels(control, 64, 64),
                    "a cache miss must not alter the canvas matrix, clip, or pixels");
        }
    }

    private static Pixel drawFrameAndRead(PanelRasterBounds crop, AtomChatConfig.PageNavStyle style,
                                          float progress) {
        ImageInfo info = new ImageInfo(crop.width(), crop.height(), ColorType.RGBA_8888,
                ColorAlphaType.PREMUL);
        try (Surface surface = Surface.makeRaster(info)) {
            Canvas canvas = surface.getCanvas();
            int baseline = canvas.getSaveCount();
            canvas.save();
            try {
                // Match SkiaGraphics: crop in physical pixels, then enter UI-density space.
                canvas.translate(-crop.x(), -crop.y());
                canvas.scale(DENSITY, DENSITY);

                PanelOpenMotion.LayerRect fadeLayer = PanelOpenMotion.fadeLayer(progress, style,
                        PANEL_X, PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT, 64.0F);
                int fadeAlpha = Math.round(255.0F * PanelOpenMotion.fade(progress));
                try (Paint fadePaint = new Paint().setColor(Color.makeARGB(fadeAlpha, 0, 0, 0))) {
                    canvas.saveLayer(Rect.makeXYWH(fadeLayer.x(), fadeLayer.y(), fadeLayer.w(), fadeLayer.h()),
                            fadePaint);
                    try {
                        float centerX = PANEL_X + PANEL_WIDTH / 2.0F;
                        float centerY = PANEL_Y + PANEL_HEIGHT / 2.0F;
                        float slide = PanelOpenMotion.openSlidePx(progress, style);
                        float scale = PanelOpenMotion.openScale(progress, style);
                        canvas.translate(centerX + slide, centerY);
                        canvas.scale(scale, scale);
                        canvas.translate(-centerX, -centerY);

                        int panelBaseline = canvas.getSaveCount();
                        assertTrue(PanelBlurRenderer.render(canvas, crop, VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                PANEL_X, PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT, PANEL_RADIUS),
                                "the injected CPU snapshot should render");
                        assertEquals(panelBaseline, canvas.getSaveCount(),
                                "the blur renderer must balance its local save stack");

                        int tint = PanelBackground.tintFor(true, true, TINT_COLOR, TINT_OPACITY);
                        try (Paint tintPaint = new Paint().setColor(tint)) {
                            canvas.drawRRect(RRect.makeXYWH(PANEL_X, PANEL_Y, PANEL_WIDTH,
                                    PANEL_HEIGHT, PANEL_RADIUS), tintPaint);
                        }
                    } finally {
                        canvas.restore();
                    }
                }
            } finally {
                canvas.restoreToCount(baseline);
            }
            assertEquals(baseline, canvas.getSaveCount(), "the composite must leave the canvas save stack balanced");
            return readPixel(surface, SAMPLE_X - crop.x(), SAMPLE_Y - crop.y());
        }
    }

    private static Image makeStripedGradientWorld() {
        try (Surface surface = Surface.makeRasterN32Premul(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0xFF25313D);
            for (int y = 0; y < VIEWPORT_HEIGHT; y += WORLD_CELL_SIZE) {
                for (int x = 0; x < VIEWPORT_WIDTH; x += WORLD_CELL_SIZE) {
                    int[] rgb = worldColor(x / WORLD_CELL_SIZE, y / WORLD_CELL_SIZE);
                    try (Paint paint = new Paint().setColor(Color.makeARGB(255, rgb[0], rgb[1], rgb[2]))) {
                        canvas.drawRect(Rect.makeXYWH(x, y, WORLD_CELL_SIZE, WORLD_CELL_SIZE), paint);
                    }
                }
            }
            return surface.makeImageSnapshot();
        }
    }

    private static int[] expectedTintedWorldPixel(int screenX, int screenY) {
        int[] world = worldColor(screenX / WORLD_CELL_SIZE, screenY / WORLD_CELL_SIZE);
        int[] tint = {TINT_COLOR >>> 16 & 0xFF, TINT_COLOR >>> 8 & 0xFF, TINT_COLOR & 0xFF};
        int tintAlpha = Math.round(TINT_OPACITY * 255.0F);
        float tintMix = tintAlpha / 255.0F;
        return new int[]{
                Math.round(world[0] * (1.0F - tintMix) + tint[0] * tintMix),
                Math.round(world[1] * (1.0F - tintMix) + tint[1] * tintMix),
                Math.round(world[2] * (1.0F - tintMix) + tint[2] * tintMix)
        };
    }

    private static int[] worldColor(int column, int row) {
        return new int[]{
                30 + (column * 37 + row * 19) % 190,
                24 + (column * 13 + row * 41) % 200,
                28 + (column * 53 + row * 17) % 190
        };
    }

    private static void prepareNoSnapshotCanvas(Canvas canvas) {
        canvas.clear(0);
        canvas.translate(7.25F, 4.5F);
        canvas.scale(1.25F, 0.8F);
    }

    private static void drawNoSnapshotControlMark(Canvas canvas) {
        try (Paint paint = new Paint().setColor(0xC8D06032)) {
            canvas.drawRRect(RRect.makeXYWH(8.0F, 7.0F, 22.0F, 18.0F, 4.0F), paint);
        }
    }

    private static Surface makeSmallSurface() {
        return Surface.makeRaster(new ImageInfo(64, 64, ColorType.RGBA_8888, ColorAlphaType.PREMUL));
    }

    private static Pixel readPixel(Surface surface, int x, int y) {
        ImageInfo info = new ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
        ByteBuffer buffer = ByteBuffer.allocateDirect(4);
        try (Pixmap pixmap = Pixmap.make(info, buffer, 4)) {
            assertTrue(surface.readPixels(pixmap, x, y), "raster sample should be readable");
            ByteBuffer pixels = pixmap.getBuffer();
            return new Pixel(pixels.get(0) & 0xFF, pixels.get(1) & 0xFF,
                    pixels.get(2) & 0xFF, pixels.get(3) & 0xFF);
        } finally {
            // The native Pixmap view does not own this direct allocation.
            java.lang.ref.Reference.reachabilityFence(buffer);
        }
    }

    private static byte[] readPixels(Surface surface, int width, int height) {
        ImageInfo info = new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        try (Pixmap pixmap = Pixmap.make(info, buffer, width * 4)) {
            assertTrue(surface.readPixels(pixmap, 0, 0), "raster pixels should be readable");
            ByteBuffer pixels = pixmap.getBuffer();
            ByteBuffer copy = ByteBuffer.allocate(width * height * 4);
            for (int index = 0; index < copy.capacity(); index++) {
                copy.put(index, pixels.get(index));
            }
            byte[] result = new byte[copy.capacity()];
            copy.get(result);
            return result;
        } finally {
            java.lang.ref.Reference.reachabilityFence(buffer);
        }
    }

    private static void withInjectedWorld(Image image, ThrowingRunnable action) throws Exception {
        Field worldField = rendererField("blurredWorld");
        Field disabledField = rendererField("disabled");
        Field widthField = rendererField("worldWidth");
        Field heightField = rendererField("worldHeight");
        Object oldWorld = worldField.get(null);
        boolean oldDisabled = disabledField.getBoolean(null);
        int oldWidth = widthField.getInt(null);
        int oldHeight = heightField.getInt(null);
        try {
            worldField.set(null, image);
            disabledField.setBoolean(null, false);
            widthField.setInt(null, VIEWPORT_WIDTH);
            heightField.setInt(null, VIEWPORT_HEIGHT);
            action.run();
        } finally {
            worldField.set(null, oldWorld);
            disabledField.setBoolean(null, oldDisabled);
            widthField.setInt(null, oldWidth);
            heightField.setInt(null, oldHeight);
        }
    }

    private static Field rendererField(String name) throws ReflectiveOperationException {
        Field field = PanelBlurRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void assertNear(int expected, int actual, int tolerance, String message) {
        assertTrue(Math.abs(expected - actual) <= tolerance,
                message + " (expected=" + expected + ", actual=" + actual + ", tolerance=" + tolerance + ")");
    }

    private static void assertPremulNear(float expected, int straightChannel, int alpha, String message) {
        float actualPremul = straightChannel * alpha / 255.0F;
        assertTrue(Math.abs(expected - actualPremul) <= MAX_PREMUL_CHANNEL_ERROR,
                message + " (expectedPremul=" + expected + ", actualPremul=" + actualPremul
                        + ", tolerance=" + MAX_PREMUL_CHANNEL_ERROR + ")");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record Pixel(int red, int green, int blue, int alpha) {
    }
}
