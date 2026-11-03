package com.atom.chat.ui.miuix;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.platform.Platform;
import com.atom.chat.render.SkiaDraw;
import com.atom.chat.ui.ToggleSwitch;
import com.atom.chat.ui.UiTokens;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MiuixControlsTest {
    private static final int SIZE = 160;
    private static final int ACCENT = 0xFF0066FF;

    @BeforeAll
    static void installTemporaryPlatform(@TempDir Path root) {
        Platform.install(new Platform.Provider() {
            @Override public Path configDir() { return root.resolve("config"); }
            @Override public Path gameDir() { return root.resolve("game"); }
            @Override public boolean isModLoaded(String modId) { return false; }
        });
    }

    @Test
    void classicStyleDoesNotTouchCanvas() {
        AtomChatConfig config = AtomChatConfig.get();
        AtomChatConfig.UiStyle previous = config.uiStyle;
        try {
            for (AtomChatConfig.UiStyle style : new AtomChatConfig.UiStyle[]{
                    AtomChatConfig.UiStyle.ATOMCHAT, null}) {
                config.uiStyle = style;
                assertFalse(MiuixControls.toggle(null, 0, 0, 1, ACCENT, true));
                assertFalse(MiuixControls.slider(null, 0, 0, 100, 6, 1, ACCENT));
            }
        } finally {
            config.uiStyle = previous;
        }
    }

    @Test
    void sliderUsesExistingCenterAndRestoresCallerClip() {
        AtomChatConfig config = AtomChatConfig.get();
        AtomChatConfig.UiStyle previous = config.uiStyle;
        try (Surface surface = Surface.makeRasterN32Premul(SIZE, SIZE)) {
            config.uiStyle = AtomChatConfig.UiStyle.MIUIX;
            for (float progress : new float[]{0, 0.5F, 1}) {
                surface.getCanvas().clear(0);
                int saves = surface.getCanvas().getSaveCount();
                assertTrue(MiuixControls.slider(surface.getCanvas(), 25, 45, 100, 10,
                        progress, ACCENT));
                assertEquals(saves, surface.getCanvas().getSaveCount());
                assertEquals(255, alphaAt(surface, Math.round(25 + progress * 100), 50),
                        "thumb center follows the original drag coordinates");
                assertEquals(0, alphaAt(surface, 75, 30), "painting stays in the old thumb band");
                SkiaDraw.drawRoundedRect(surface.getCanvas(), 2, 2, 8, 8, 0, MiuixPalette.thumb());
                assertEquals(255, alphaAt(surface, 5, 5), "slider must not leak its track clip");
                assertEquals(pixelAt(surface, 5, 5), pixelAt(surface, Math.round(25 + progress * 100), 50),
                        "the round thumb, rather than only the track, follows the drag position");
            }
        } finally {
            config.uiStyle = previous;
        }
    }

    @Test
    void switchKeepsOuterBoundsAndDimsEntireDisabledControl() {
        AtomChatConfig config = AtomChatConfig.get();
        AtomChatConfig.UiStyle previous = config.uiStyle;
        try (Surface surface = Surface.makeRasterN32Premul(SIZE, SIZE)) {
            config.uiStyle = AtomChatConfig.UiStyle.MIUIX;
            ToggleSwitch control = new ToggleSwitch();
            for (boolean enabled : new boolean[]{true, false}) {
                surface.getCanvas().clear(0);
                control.snapTo(true);
                int saves = surface.getCanvas().getSaveCount();
                control.render(surface.getCanvas(), 25, 30, ACCENT, enabled);
                assertEquals(saves, surface.getCanvas().getSaveCount());
                int expectedAlpha = enabled ? 255 : (MiuixPalette.disabled(ACCENT) >>> 24);
                assertEquals(expectedAlpha, alphaAt(surface, 30, Math.round(30 + UiTokens.SWITCH_H / 2)),
                        "the on track receives the disabled opacity too");
                assertEquals(0, alphaAt(surface, 24, 40));
                assertEquals(0, alphaAt(surface, Math.round(26 + UiTokens.SWITCH_W), 40));
            }
        } finally {
            config.uiStyle = previous;
        }
    }

    @Test
    void controlsRespectSquareCornersAndKeepPaleAccentThumbVisibleOnDarkCards() {
        AtomChatConfig config = AtomChatConfig.get();
        AtomChatConfig.UiStyle previousStyle = config.uiStyle;
        float previousRadius = config.cornerRadius;
        int previousCard = config.cardColor;
        int previousPanel = config.panelBgColor;
        int previousAccent = config.accentColor;
        try (Surface surface = Surface.makeRasterN32Premul(SIZE, SIZE)) {
            config.uiStyle = AtomChatConfig.UiStyle.MIUIX;
            config.cardColor = 0xFF151515;
            config.panelBgColor = 0xFF151515;
            config.accentColor = 0xFFFFF0F0;
            config.cornerRadius = 20;
            surface.getCanvas().clear(0);
            MiuixControls.slider(surface.getCanvas(), 30, 45, 100, 10, 0, config.accentColor);
            assertEquals(0, alphaAt(surface, 20, 40), "default thumb is circular");
            int thumb = pixelAt(surface, 30, 50);
            assertTrue((thumb & 0xFF) > 200, "thumb stays light against the dark neutral track");
            config.cornerRadius = 0;
            surface.getCanvas().clear(0);
            MiuixControls.slider(surface.getCanvas(), 30, 45, 100, 10, 0, config.accentColor);
            assertEquals(255, alphaAt(surface, 20, 40), "zero radius reaches the slider thumb");
            MiuixControls.toggle(surface.getCanvas(), 25, 90, 1, config.accentColor, true);
            assertEquals(255, alphaAt(surface, 25, 90), "zero radius reaches the switch track");
        } finally {
            config.uiStyle = previousStyle;
            config.cornerRadius = previousRadius;
            config.cardColor = previousCard;
            config.panelBgColor = previousPanel;
            config.accentColor = previousAccent;
        }
    }

    private static int alphaAt(Surface surface, int x, int y) {
        return pixelAt(surface, x, y) >>> 24;
    }

    private static int pixelAt(Surface surface, int x, int y) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(SIZE, SIZE);
            assertTrue(surface.readPixels(bitmap, 0, 0));
            byte[] pixels = bitmap.readPixels();
            int offset = (y * SIZE + x) * 4;
            return (pixels[offset + 3] & 0xFF) << 24 | (pixels[offset + 2] & 0xFF) << 16
                    | (pixels[offset + 1] & 0xFF) << 8 | (pixels[offset] & 0xFF);
        }
    }
}
