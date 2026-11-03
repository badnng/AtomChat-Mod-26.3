package com.atom.chat.ui.miuix;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.platform.Platform;
import com.atom.chat.ui.BottomTabBar;
import com.atom.chat.ui.UiLayout;
import com.atom.chat.ui.UiTokens;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MiuixNavigationTest {
    private static final int TEXT = 0xFFFFFFFF;
    private static final int ACCENT = 0xFF598BFF;
    private static final String[] LABELS = {"聊天", "个人资料", "设置"};

    @BeforeAll
    static void platform(@TempDir Path root) {
        Platform.install(new Platform.Provider() {
            @Override public Path configDir() { return root.resolve("config"); }
            @Override public Path gameDir() { return root.resolve("game"); }
            @Override public boolean isModLoaded(String modId) { return false; }
        });
    }

    @Test
    void allModesRenderAtNarrowAndWideWidthsWithAllSelectedTabs() throws Exception {
        Map<AtomChatConfig.MiuixNavigationStyle, byte[]> signatures =
                new EnumMap<>(AtomChatConfig.MiuixNavigationStyle.class);
        try (Fixture fixture = new Fixture()) {
            for (AtomChatConfig.MiuixNavigationStyle mode : AtomChatConfig.MiuixNavigationStyle.values()) {
                fixture.config.miuixNavigationStyle = mode;
                try (Surface surface = Surface.makeRasterN32Premul(1140, 330)) {
                    surface.getCanvas().clear(0);
                    for (int selected = 0; selected < 3; selected++) {
                        for (int width : new int[]{320, 720}) {
                            UiLayout.Rect slot = new UiLayout.Rect(width == 320 ? 20 : 390,
                                    20 + selected * 100, width, UiTokens.TAB_BAR_H);
                            BottomTabBar tabs = new BottomTabBar();
                            tabs.setSelectedImmediate(selected);
                            int depth = surface.getCanvas().getSaveCount();
                            tabs.render(surface.getCanvas(), slot, selected, TEXT, ACCENT, LABELS);
                            assertEquals(depth, surface.getCanvas().getSaveCount(), mode + " save stack");
                            UiLayout.Rect bounds = BottomTabBar.navigationBounds(slot);
                            for (int i = 0; i < 3; i++) {
                                float cx = bounds.x() + bounds.w() * (i + 0.5F) / 3;
                                float cy = bounds.y() + bounds.h() / 2;
                                assertEquals(i, BottomTabBar.hitTest(cx, cy, slot), mode + " drawn cell");
                                assertTrue(alphaAt(surface, Math.round(cx), Math.round(cy)) > 0,
                                        mode + " cell is painted");
                            }
                            if (mode == AtomChatConfig.MiuixNavigationStyle.FLOATING) {
                                assertEquals(-1, BottomTabBar.hitTest(slot.x() + 1, slot.y() + slot.h() / 2, slot));
                                assertEquals(-1, BottomTabBar.hitTest(slot.right() - 1, slot.y() + slot.h() / 2, slot));
                                assertTrue(bounds.w() < slot.w());
                                assertEquals(slot.x() + slot.w() / 2, bounds.x() + bounds.w() / 2, 0.001F);
                            } else {
                                assertEquals(slot, bounds);
                            }
                        }
                    }
                    signatures.put(mode, pixels(surface));
                    Path output = Path.of("build", "miuix-style-render", "nav-" + mode.name().toLowerCase(java.util.Locale.ROOT) + ".png");
                    Files.createDirectories(output.getParent());
                    try (Image image = surface.makeImageSnapshot(); Data png = image.encodeToData()) {
                        Files.write(output, png.getBytes());
                    }
                }
            }
            for (AtomChatConfig.MiuixNavigationStyle a : signatures.keySet()) {
                for (AtomChatConfig.MiuixNavigationStyle b : signatures.keySet()) {
                    if (a != b) assertFalse(Arrays.equals(signatures.get(a), signatures.get(b)),
                            a + " and " + b + " must be visually distinct");
                }
            }
        }
    }

    @Test
    void classicAndNullStyleKeepOriginalBoundsAndPixels() {
        try (Fixture fixture = new Fixture();
             Surface expected = Surface.makeRasterN32Premul(380, 100);
             Surface actual = Surface.makeRasterN32Premul(380, 100)) {
            UiLayout.Rect slot = new UiLayout.Rect(20, 20, 320, UiTokens.TAB_BAR_H);
            fixture.config.uiStyle = AtomChatConfig.UiStyle.ATOMCHAT;
            fixture.config.miuixNavigationStyle = AtomChatConfig.MiuixNavigationStyle.ICON_ONLY;
            BottomTabBar tabs = new BottomTabBar();
            tabs.setSelectedImmediate(1);
            expected.getCanvas().clear(0);
            tabs.render(expected.getCanvas(), slot, 1, TEXT, ACCENT);
            fixture.config.uiStyle = null;
            fixture.config.miuixNavigationStyle = AtomChatConfig.MiuixNavigationStyle.FLOATING;
            actual.getCanvas().clear(0);
            tabs.render(actual.getCanvas(), slot, 1, TEXT, ACCENT, LABELS);
            assertArrayEquals(pixels(expected), pixels(actual));
            assertEquals(slot, BottomTabBar.navigationBounds(slot));
            assertEquals(0, BottomTabBar.hitTest(slot.x() + 1, slot.y() + slot.h() / 2, slot));
            assertEquals(-1, BottomTabBar.hitTest(slot.x() - 1, slot.y(), slot));
            assertNull(BottomTabBar.navigationBounds(null));
            assertEquals(-1, BottomTabBar.hitTest(0, 0, null));
        }
    }

    @Test
    void missingModeAndLabelsFallBackAndSelectionKeepsItsAnimator() {
        try (Fixture fixture = new Fixture(); Surface surface = Surface.makeRasterN32Premul(380, 100)) {
            UiLayout.Rect slot = new UiLayout.Rect(20, 20, 320, UiTokens.TAB_BAR_H);
            fixture.config.miuixNavigationStyle = null;
            assertEquals(AtomChatConfig.MiuixNavigationStyle.ICON_AND_TEXT,
                    fixture.config.effectiveMiuixNavigationStyle());
            BottomTabBar tabs = new BottomTabBar();
            tabs.setSelectedIndex(2);
            tabs.update(1000, 30, 50, slot);
            assertEquals(2, tabs.indicatorValue(), 0.001F);
            assertDoesNotThrow(() -> tabs.render(surface.getCanvas(), slot, 2, TEXT, ACCENT,
                    new String[]{null}));
        }
    }

    private static byte[] pixels(Surface surface) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(surface.getWidth(), surface.getHeight());
            assertTrue(surface.readPixels(bitmap, 0, 0));
            return bitmap.readPixels();
        }
    }

    private static int alphaAt(Surface surface, int x, int y) {
        return pixels(surface)[(y * surface.getWidth() + x) * 4 + 3] & 0xFF;
    }

    private static final class Fixture implements AutoCloseable {
        final AtomChatConfig config = AtomChatConfig.get();
        final AtomChatConfig.UiStyle style = config.uiStyle;
        final AtomChatConfig.MiuixNavigationStyle navigationStyle = config.miuixNavigationStyle;

        Fixture() { config.uiStyle = AtomChatConfig.UiStyle.MIUIX; }

        @Override public void close() {
            config.uiStyle = style;
            config.miuixNavigationStyle = navigationStyle;
        }
    }
}
