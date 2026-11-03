package com.atom.chat.ui.miuix;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.platform.Platform;
import com.atom.chat.ui.UiCards;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiuixComponentsTest {
    @BeforeAll
    static void installTemporaryPlatform(@TempDir Path root) {
        Platform.install(new Platform.Provider() {
            public Path configDir() { return root.resolve("config"); }
            public Path gameDir() { return root.resolve("game"); }
            public boolean isModLoaded(String modId) { return false; }
        });
    }

    @Test
    void fillAndClipUseTheSameContour() {
        try (Surface surface = Surface.makeRasterN32Premul(96, 64)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0);
            MiuixComponents.surface(canvas, 8, 8, 80, 48, 20, 0xFFFFFFFF);
            byte[] filled = pixels(surface);
            canvas.clear(0);
            canvas.save();
            try {
                MiuixComponents.clip(canvas, 8, 8, 80, 48, 20);
                canvas.clear(0xFFFFFFFF);
            } finally {
                canvas.restore();
            }
            byte[] clipped = pixels(surface);
            int interiorPixels = 0;
            int exteriorPixels = 0;
            int edgePixels = 0;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 96; x++) {
                    int index = (y * 96 + x) * 4;
                    int alpha = filled[index + 3] & 0xFF;
                    boolean edge = alpha != 0 && alpha != 255;
                    for (int ny = Math.max(0, y - 1); ny <= Math.min(63, y + 1); ny++) {
                        for (int nx = Math.max(0, x - 1); nx <= Math.min(95, x + 1); nx++) {
                            edge |= (filled[(ny * 96 + nx) * 4 + 3] & 0xFF) != alpha;
                        }
                    }
                    if (edge) {
                        edgePixels++;
                    } else if (alpha == 255) {
                        interiorPixels++;
                    } else {
                        exteriorPixels++;
                    }
                    for (int channel = 0; channel < 4; channel++) {
                        int expected = filled[index + channel] & 0xFF;
                        int actual = clipped[index + channel] & 0xFF;
                        // Skia's path and clip rasterizers round AA coverage
                        // independently. Only the one-pixel contour band may
                        // differ, by at most two coverage levels; solid inside
                        // and outside regions must still match exactly.
                        int tolerance = edge ? 2 : 0;
                        assertTrue(Math.abs(expected - actual) <= tolerance,
                                "fill/clip contour mismatch at " + x + "," + y
                                        + " channel " + channel + ": " + expected + " vs " + actual);
                    }
                }
            }
            assertTrue(interiorPixels > 0);
            assertTrue(exteriorPixels > 0);
            assertTrue(edgePixels > 0);
        }
    }

    @Test
    void repeatedFramesKeepSaveCountNativePathsAndBlurCacheUsable() {
        AtomChatConfig config = AtomChatConfig.get();
        AtomChatConfig.UiStyle oldStyle = config.uiStyle;
        config.uiStyle = AtomChatConfig.UiStyle.MIUIX;
        try (Surface surface = Surface.makeRasterN32Premul(96, 64)) {
            Canvas canvas = surface.getCanvas();
            int baseline = canvas.getSaveCount();
            for (int frame = 0; frame < 100; frame++) {
                canvas.clear(0);
                MiuixComponents.shadow(canvas, 8, 8, 80, 48, 18, 0x22000000,
                        1.0F + frame % 20, 1.0F);
                MiuixComponents.chrome(canvas, 8, 8, 80, 48, 18);
                MiuixComponents.border(canvas, 8, 8, 80, 48, 18, 1, 0x88FFFFFF);
                MiuixComponents.button(canvas, 20, 20, 40, 24, 10,
                        frame % 2 == 0, true, frame % 3 == 0, frame % 5 == 0);
                UiCards.drawCard(canvas, 10, 10, 76, 44, 18, 0.5F);
                UiCards.drawFloatSurface(canvas, 12, 12, 72, 40, 18, 0.5F, 0xFF445566);
                assertEquals(baseline, canvas.getSaveCount());
                assertTrue(MiuixComponents.cachedBlurCount() <= 16);
            }
            assertTrue(alphaAt(surface, 48, 32) > 0);
        } finally {
            config.uiStyle = oldStyle;
        }
    }

    private static byte[] pixels(Surface surface) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(96, 64);
            assertTrue(surface.readPixels(bitmap, 0, 0));
            return bitmap.readPixels();
        }
    }

    private static int alphaAt(Surface surface, int x, int y) {
        return pixels(surface)[(y * 96 + x) * 4 + 3] & 0xFF;
    }
}
