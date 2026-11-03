package com.atom.chat.render;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.platform.Platform;
import com.atom.chat.settings.SettingsHomePage;
import com.atom.chat.settings.SettingsSection;
import com.atom.chat.settings.SettingsSectionPage;
import com.atom.chat.ui.UiLayout;
import com.atom.chat.ui.UiTokens;
import com.atom.chat.ui.UiSurfaces;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real page render integration: native ownership, style routing and square-corner regression. */
class MiuixStyleRenderTest {
    @TempDir Path fixture;
    private Field providerField;
    private Field instanceField;
    private Object originalProvider;
    private Object originalInstance;

    @BeforeEach
    void installIsolatedConfig() throws Exception {
        providerField = Platform.class.getDeclaredField("provider");
        providerField.setAccessible(true);
        originalProvider = providerField.get(null);
        instanceField = AtomChatConfig.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        originalInstance = instanceField.get(null);
        Platform.install(new Platform.Provider() {
            public Path configDir() { return fixture.resolve("config"); }
            public Path gameDir() { return fixture.resolve("game"); }
            public boolean isModLoaded(String id) { return false; }
        });
        AtomChatConfig.reload();
    }

    @AfterEach
    void restoreGlobalsForOtherRenderTests() throws Exception {
        providerField.set(null, originalProvider);
        instanceField.set(null, originalInstance);
    }

    @Test
    void bothStylesRenderRealPagesAtNarrowAndWideWidthsWithoutChangingAppearance() throws Exception {
        var config = AtomChatConfig.get();
        config.themeName = "custom-style-render-fixture";
        config.accentColor = 0xFFB05C95;
        config.cardColor = 0xE6DDD4C9;
        config.panelBgColor = 0xEA202934;
        for (int width : new int[]{320, 720}) {
            for (float radius : new float[]{0.0F, 20.0F}) {
                config.cornerRadius = radius;
                Map<String, Object> appearance = appearance(config);
                Map<String, byte[]> atomchat = renderPages(width, radius, AtomChatConfig.UiStyle.ATOMCHAT);
                Map<String, byte[]> miuix = renderPages(width, radius, AtomChatConfig.UiStyle.MIUIX);
                for (String page : atomchat.keySet()) {
                    assertFalse(Arrays.equals(atomchat.get(page), miuix.get(page)),
                            "UI selector did not change " + page + " pixels at width " + width + ", radius " + radius);
                }
                assertEquals(appearance, appearance(config), "Rendering overwrote appearance preferences");
                if (radius == 0.0F) assertEquals(0.0F, UiTokens.cardRadius(), "Square corners acquired a radius");
            }
        }
    }

    private Map<String, byte[]> renderPages(int width, float radius, AtomChatConfig.UiStyle style) throws Exception {
        AtomChatConfig.get().uiStyle = style;
        UiLayout layout = UiLayout.ofDetail(20, 20, width, 780);
        var home = new SettingsHomePage();
        var page = new SettingsSectionPage();
        assertTrue(page.rows(SettingsSection.APPEARANCE).stream()
                .anyMatch(row -> "ui_style".equals(row.actionId())), "Theme page lost its style selector");
        try (Surface surface = Surface.makeRasterN32Premul(width + 40, 840)) {
            var canvas = surface.getCanvas();
            int baseline = canvas.getSaveCount();
            Map<String, byte[]> frames = new LinkedHashMap<>();
            for (int repeat = 0; repeat < 3; repeat++) {
                canvas.clear(0);
                UiSurfaces.chrome(canvas, 20, 20, width, 70, UiTokens.cardRadius());
                canvas.save();
                try {
                    UiSurfaces.clip(canvas, 20, 120, width, 100, UiTokens.cardRadius());
                    UiSurfaces.background(canvas, 0, 100, width + 40, 140,
                            UiTokens.cardRadius(), AtomChatConfig.get().accentColor);
                } finally {
                    canvas.restore();
                }
                assertEquals(baseline, canvas.getSaveCount(), style + " chrome/clip leaked a canvas save");
                UiSurfaces.background(canvas, 2, 300, 8, 8, 0, AtomChatConfig.get().accentColor);
                byte[] chrome = assertPainted(surface, width + 40, 840, style + " chrome");
                assertEquals(255, alphaAt(chrome, width + 40, 5, 303), "Clip escaped the caller restore");
                assertEquals(0, alphaAt(chrome, width + 40, 10, 170), "Content escaped the component clip");
                if (radius == 0.0F) {
                    assertEquals(255, alphaAt(chrome, width + 40, 20, 120),
                            style + " rounded a square-configured component corner");
                }
                frames.put("chrome", chrome);
                canvas.clear(0);
                home.render(canvas, layout, -1, -1, 0);
                assertEquals(baseline, canvas.getSaveCount(), style + " home leaked a canvas save");
                frames.put("home", assertPainted(surface, width + 40, 840, style + " home"));
                for (SettingsSection section : new SettingsSection[]{SettingsSection.APPEARANCE, SettingsSection.CHAT}) {
                    canvas.clear(0);
                    page.render(canvas, layout, section, -1, -1, 0, AtomChatConfig.get().accentColor);
                    assertEquals(baseline, canvas.getSaveCount(), style + " " + section + " leaked a canvas save");
                    frames.put(section.id(), assertPainted(surface, width + 40, 840, style + " " + section));
                    if (repeat == 2) writeScreenshot(surface, style, width, radius, section);
                }
            }
            return frames;
        }
    }

    private static byte[] assertPainted(Surface surface, int width, int height, String what) {
        try (Bitmap bitmap = new Bitmap()) {
            bitmap.allocN32Pixels(width, height);
            assertTrue(surface.readPixels(bitmap, 0, 0));
            byte[] pixels = bitmap.readPixels();
            int painted = 0;
            for (int index = 3; index < pixels.length; index += 4) {
                if (pixels[index] != 0) painted++;
            }
            assertTrue(painted > width * 50, what + " failed to paint visible page content");
            return pixels;
        }
    }

    private static int alphaAt(byte[] pixels, int width, int x, int y) {
        return pixels[(y * width + x) * 4 + 3] & 0xFF;
    }

    private static void writeScreenshot(Surface surface, AtomChatConfig.UiStyle style, int width,
                                        float radius, SettingsSection section) throws Exception {
        Path root = Path.of(System.getProperty("atomchat.projectDir", ""))
                .resolve("build/miuix-style-render");
        Files.createDirectories(root);
        try (Image image = surface.makeImageSnapshot(); Data data = image.encodeToData(EncodedImageFormat.PNG)) {
            assertNotNull(data);
            Files.write(root.resolve(style.name().toLowerCase(java.util.Locale.ROOT) + "-" + width
                    + "-radius-" + (int) radius + "-" + section.id() + ".png"), data.getBytes());
        }
    }

    private static Map<String, Object> appearance(AtomChatConfig config) throws IllegalAccessException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (var field : AtomChatConfig.class.getFields()) {
            if (field.getName().endsWith("Color") || field.getName().equals("themeName")
                    || field.getName().equals("cornerRadius")) values.put(field.getName(), field.get(config));
        }
        return values;
    }
}
