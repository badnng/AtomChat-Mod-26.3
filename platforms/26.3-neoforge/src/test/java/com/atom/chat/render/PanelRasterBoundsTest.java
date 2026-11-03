package com.atom.chat.render;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.ui.PanelOpenMotion;
import com.atom.chat.ui.UiTokens;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Color;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Pixmap;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelRasterBoundsTest {
    @Test
    void default1080pPanelKeepsTheWholeAnimationInABoundedRaster() {
        int viewportWidth = 1920;
        int viewportHeight = 1080;
        PanelRasterBounds bounds = PanelRasterBounds.of(viewportWidth, viewportHeight, 1.0F,
                30.0F, 16.0F, 600.0F, 1048.0F);

        assertEquals(0, bounds.x(), "the default anchor plus slide and shadow reaches the left edge");
        assertEquals(0, bounds.y(), "the tall default panel plus shadow reaches the top edge");
        assertTrue(bounds.width() < viewportWidth);
        assertEquals(viewportHeight, bounds.height());
        double areaRatio = (double) bounds.width() * bounds.height() / (viewportWidth * (double) viewportHeight);
        assertTrue(areaRatio > 0.30D && areaRatio < 0.40D,
                "the crop should cover about a third of the display, got " + areaRatio);
    }

    @Test
    void fixedBoundsContainSlideAndZoomFadeLayersAtFractionalDensity() {
        float density = 1.25F;
        float panelX = 140.25F;
        float panelY = 120.5F;
        float panelWidth = 600.25F;
        float panelHeight = 900.5F;
        PanelRasterBounds bounds = PanelRasterBounds.of(1920, 1600, density,
                panelX, panelY, panelWidth, panelHeight);

        for (AtomChatConfig.PageNavStyle style : AtomChatConfig.PageNavStyle.values()) {
            for (float progress : new float[]{0.0F, 0.25F, 0.5F, 0.75F, 1.0F}) {
                PanelOpenMotion.LayerRect pose = PanelOpenMotion.fadeLayer(progress, style,
                        panelX, panelY, panelWidth, panelHeight, 64.0F);
                assertContains(bounds, pose, density, style + " progress " + progress);
            }
        }
    }

    @Test
    void fractionalDensityRoundsOutwardAndClampsTheTopEdge() {
        PanelRasterBounds bounds = PanelRasterBounds.of(900, 900, 1.25F,
                100.3F, 100.4F, 300.2F, 400.1F);

        assertEquals(new PanelRasterBounds(7, 45, 574, 661), bounds);
    }

    @Test
    void largePanelIsClampedToASmallViewportAndOffscreenPanelGetsOnePixel() {
        assertEquals(new PanelRasterBounds(0, 0, 320, 200),
                PanelRasterBounds.of(320, 200, 2.5F, 20.0F, 15.0F, 600.0F, 1000.0F));
        assertEquals(new PanelRasterBounds(319, 199, 1, 1),
                PanelRasterBounds.of(320, 200, 1.0F, 10000.0F, 10000.0F, 100.0F, 100.0F));
        assertEquals(new PanelRasterBounds(0, 0, 1, 1),
                PanelRasterBounds.of(320, 200, 1.0F, -10000.0F, -10000.0F, 100.0F, 100.0F));
        assertEquals(new PanelRasterBounds(0, 0, 1, 1),
                PanelRasterBounds.of(1, 1, 1.5F, 0.0F, 0.0F, 600.0F, 1000.0F));
    }

    @Test
    void rejectsInvalidViewportAndDensity() {
        assertThrows(IllegalArgumentException.class,
                () -> PanelRasterBounds.of(0, 100, 1.0F, 0.0F, 0.0F, 10.0F, 10.0F));
        assertThrows(IllegalArgumentException.class,
                () -> PanelRasterBounds.of(100, 100, Float.NaN, 0.0F, 0.0F, 10.0F, 10.0F));
    }

    @Test
    void croppedRasterMatchesFullRasterForPhysicalBlurAndTransformedDesignUi() {
        int viewportWidth = 800;
        int viewportHeight = 700;
        float density = 1.25F;
        float panelX = 160.25F;
        float panelY = 100.5F;
        float panelWidth = 320.25F;
        float panelHeight = 360.5F;
        float stroke = UiTokens.PANEL_BEZEL_WIDTH;
        PanelRasterBounds crop = PanelRasterBounds.of(viewportWidth, viewportHeight, density,
                panelX, panelY, panelWidth, panelHeight);
        assertTrue(crop.x() > 0 && crop.y() > 0, "exercise a non-zero crop origin");

        ImageInfo pixels = new ImageInfo(viewportWidth, viewportHeight,
                ColorType.RGBA_8888, ColorAlphaType.PREMUL);
        Image worldSnapshot = makeWorldSnapshot();
        try (worldSnapshot;
             Surface full = Surface.makeRaster(pixels);
             Surface cropped = Surface.makeRaster(new ImageInfo(crop.width(), crop.height(),
                     ColorType.RGBA_8888, ColorAlphaType.PREMUL))) {
            for (AtomChatConfig.PageNavStyle style : AtomChatConfig.PageNavStyle.values()) {
                float progress = style == AtomChatConfig.PageNavStyle.SLIDE ? 0.42F : 0.63F;
                drawTestFrame(full.getCanvas(), null, worldSnapshot, viewportWidth, viewportHeight,
                        density, panelX, panelY, panelWidth, panelHeight, stroke, style, progress);
                drawTestFrame(cropped.getCanvas(), crop, worldSnapshot, viewportWidth, viewportHeight,
                        density, panelX, panelY, panelWidth, panelHeight, stroke, style, progress);

                byte[] fullPixels = readPixels(full, viewportWidth, viewportHeight);
                byte[] cropPixels = readPixels(cropped, crop.width(), crop.height());
                assertCropMatches(fullPixels, cropPixels, crop, viewportWidth, viewportHeight, style);
            }
        }
    }

    private static Image makeWorldSnapshot() {
        try (Surface source = Surface.makeRasterN32Premul(96, 72)) {
            Canvas canvas = source.getCanvas();
            canvas.clear(0xFF26384A);
            for (int row = 0; row < 6; row++) {
                for (int column = 0; column < 8; column++) {
                    int red = 35 + column * 17;
                    int green = 45 + row * 22;
                    int blue = 110 + ((column + row) % 4) * 25;
                    try (Paint paint = new Paint().setColor(Color.makeARGB(255, red, green, blue))) {
                        canvas.drawRect(Rect.makeXYWH(column * 12.0F, row * 12.0F, 12.0F, 12.0F), paint);
                    }
                }
            }
            return source.makeImageSnapshot();
        }
    }

    /** Mirrors SkiaGraphics' physical pre-pass, then design-density and phone transform. */
    private static void drawTestFrame(Canvas canvas, PanelRasterBounds crop, Image worldSnapshot,
                                      int viewportWidth, int viewportHeight, float density,
                                      float panelX, float panelY, float panelWidth, float panelHeight,
                                      float stroke, AtomChatConfig.PageNavStyle style, float progress) {
        canvas.clear(0);
        int baseline = canvas.getSaveCount();
        canvas.save();
        try {
            if (crop != null) {
                canvas.translate(-crop.x(), -crop.y());
            }
            drawPhysicalBlurPrepass(canvas, worldSnapshot, viewportWidth, viewportHeight, density,
                    panelX, panelY, panelWidth, panelHeight, stroke, style, progress);

            canvas.scale(density, density);
            drawTransformedPanel(canvas, panelX, panelY, panelWidth, panelHeight, style, progress);
        } finally {
            canvas.restoreToCount(baseline);
        }
    }

    private static void drawPhysicalBlurPrepass(Canvas canvas, Image worldSnapshot,
                                                int viewportWidth, int viewportHeight, float density,
                                                float panelX, float panelY, float panelWidth, float panelHeight,
                                                float stroke, AtomChatConfig.PageNavStyle style, float progress) {
        PanelOpenMotion.PanelRect capture = PanelOpenMotion.blurCapture(progress, style,
                panelX, panelY, panelWidth, panelHeight, stroke, density, 1.0D);
        int baseline = canvas.getSaveCount();
        canvas.save();
        try (Paint paint = new Paint().setAlphaf(0.78F)) {
            canvas.clipRRect(RRect.makeXYWH(capture.x(), capture.y(), capture.w(), capture.h(), capture.radius()), true);
            canvas.drawImageRect(worldSnapshot, Rect.makeWH(worldSnapshot.getWidth(), worldSnapshot.getHeight()),
                    Rect.makeWH(viewportWidth, viewportHeight), SamplingMode.LINEAR, paint, false);
        } finally {
            canvas.restoreToCount(baseline);
        }
    }

    private static void drawTransformedPanel(Canvas canvas, float panelX, float panelY,
                                             float panelWidth, float panelHeight,
                                             AtomChatConfig.PageNavStyle style, float progress) {
        PanelOpenMotion.LayerRect layerBounds = PanelOpenMotion.fadeLayer(progress, style,
                panelX, panelY, panelWidth, panelHeight, 64.0F);
        int alpha = Math.round(255.0F * PanelOpenMotion.fade(progress));
        int baseline = canvas.getSaveCount();
        canvas.save();
        try (Paint layer = new Paint().setColor(Color.makeARGB(alpha, 0, 0, 0))) {
            canvas.saveLayer(Rect.makeXYWH(layerBounds.x(), layerBounds.y(), layerBounds.w(), layerBounds.h()), layer);
            try {
                float centerX = panelX + panelWidth / 2.0F;
                float centerY = panelY + panelHeight / 2.0F;
                float slide = PanelOpenMotion.openSlidePx(progress, style);
                float scale = PanelOpenMotion.openScale(progress, style);
                canvas.translate(centerX + slide, centerY);
                canvas.scale(scale, scale);
                canvas.translate(-centerX, -centerY);
                drawPanelLayers(canvas, panelX, panelY, panelWidth, panelHeight);
            } finally {
                canvas.restore();
            }
        } finally {
            canvas.restoreToCount(baseline);
        }
    }

    private static void drawPanelLayers(Canvas canvas, float x, float y, float width, float height) {
        RRect panel = RRect.makeXYWH(x, y, width, height, 30.0F);
        try (ImageFilter shadow = ImageFilter.makeDropShadowOnly(0.0F, 5.0F, 10.0F, 12.0F, 0xB0000000);
             Paint shadowPaint = new Paint().setImageFilter(shadow);
             Paint panelPaint = new Paint().setColor(0xD51A2430);
             Paint headerPaint = new Paint().setColor(0xB83C5974);
             Paint cardPaint = new Paint().setColor(0xAEEFCE83)) {
            canvas.drawRRect(panel, shadowPaint);
            canvas.drawRRect(panel, panelPaint);
            canvas.drawRRect(RRect.makeXYWH(x + 18.0F, y + 24.0F, width - 36.0F, 46.0F, 14.0F), headerPaint);
            canvas.drawRRect(RRect.makeXYWH(x + 24.0F, y + 94.0F, width - 48.0F, 110.0F, 18.0F), cardPaint);
        }
    }

    private static byte[] readPixels(Surface surface, int width, int height) {
        ImageInfo info = new ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL);
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
        try (Pixmap pixmap = Pixmap.make(info, buffer, width * 4)) {
            assertTrue(surface.readPixels(pixmap, 0, 0), "raster pixels should be readable");
            ByteBuffer pixels = pixmap.getBuffer();
            byte[] copy = new byte[width * height * 4];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = pixels.get(i);
            }
            return copy;
        } finally {
            // Pixmap retains the native address, not the owning direct buffer.
            java.lang.ref.Reference.reachabilityFence(buffer);
        }
    }

    private static void assertCropMatches(byte[] fullPixels, byte[] cropPixels, PanelRasterBounds crop,
                                          int viewportWidth, int viewportHeight,
                                          AtomChatConfig.PageNavStyle style) {
        boolean foundVisiblePixel = false;
        int differingPixels = 0;
        int differingChannels = 0;
        int maxChannelError = 0;
        int maxErrorAlpha = 0;
        int maxErrorX = -1;
        int maxErrorY = -1;
        int maxErrorChannel = -1;
        for (int y = 0; y < viewportHeight; y++) {
            for (int x = 0; x < viewportWidth; x++) {
                int fullOffset = (y * viewportWidth + x) * 4;
                int alpha = fullPixels[fullOffset + 3] & 0xFF;
                boolean inCrop = x >= crop.x() && x < crop.x() + crop.width()
                        && y >= crop.y() && y < crop.y() + crop.height();
                if (!inCrop) {
                    if (alpha != 0) {
                        assertEquals(0, alpha, "crop must contain every drawn pixel at " + x + "," + y);
                    }
                    continue;
                }
                foundVisiblePixel |= alpha != 0;
                int cropOffset = ((y - crop.y()) * crop.width() + (x - crop.x())) * 4;
                boolean pixelDiffers = false;
                for (int channel = 0; channel < 4; channel++) {
                    int expected = fullPixels[fullOffset + channel] & 0xFF;
                    int actual = cropPixels[cropOffset + channel] & 0xFF;
                    int error = Math.abs(expected - actual);
                    if (error > 0) {
                        pixelDiffers = true;
                        differingChannels++;
                        if (error > maxChannelError) {
                            maxChannelError = error;
                            maxErrorAlpha = alpha;
                            maxErrorX = x;
                            maxErrorY = y;
                            maxErrorChannel = channel;
                        }
                    }
                }
                if (pixelDiffers) {
                    differingPixels++;
                }
            }
        }
        assertTrue(foundVisiblePixel, "the rendered panel should contribute non-transparent pixels");
        double diffRatio = differingPixels / (double) (crop.width() * crop.height());
        int maxAllowedDifferingPixels = Math.max(8, crop.width() * crop.height() / 10_000);
        String metrics = style + " crop deltas: differingPixels=" + differingPixels
                + "/" + (crop.width() * crop.height()) + " (" + diffRatio + "), differingChannels="
                + differingChannels + ", maxChannelError=" + maxChannelError + " at (" + maxErrorX + ","
                + maxErrorY + ") channel " + maxErrorChannel + " with pixel alpha " + maxErrorAlpha;
        // PREMUL readPixels avoids UNPREMUL amplification. Skia can still vary a
        // few edge channels between the full and translated raster due to AA /
        // image-filter quantization, so bound both the magnitude and affected area.
        assertTrue(maxChannelError <= 4, metrics);
        assertTrue(differingPixels <= maxAllowedDifferingPixels,
                metrics + "; limit=" + maxAllowedDifferingPixels + " (0.01% of crop, minimum 8 pixels)");
    }

    private static void assertContains(PanelRasterBounds bounds, PanelOpenMotion.LayerRect pose,
                                       float density, String description) {
        double left = (double) pose.x() * density;
        double top = (double) pose.y() * density;
        double right = ((double) pose.x() + pose.w()) * density;
        double bottom = ((double) pose.y() + pose.h()) * density;
        assertTrue(bounds.x() <= left, description + " left edge");
        assertTrue(bounds.y() <= top, description + " top edge");
        assertTrue(bounds.x() + bounds.width() >= right, description + " right edge");
        assertTrue(bounds.y() + bounds.height() >= bottom, description + " bottom edge");
    }
}
