package com.atom.chat.config;

import com.atom.chat.platform.Platform;
import com.atom.chat.theme.ThemeService;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStyleConfigTest {
    @TempDir Path dir;
    private Field providerField;
    private Field instanceField;
    private Object originalProvider;
    private Object originalInstance;

    @BeforeEach
    void installTemporaryConfig() throws Exception {
        providerField = Platform.class.getDeclaredField("provider");
        providerField.setAccessible(true);
        originalProvider = providerField.get(null);
        instanceField = AtomChatConfig.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        originalInstance = instanceField.get(null);
        Platform.install(new Platform.Provider() {
            public Path configDir() { return dir; }
            public Path gameDir() { return dir; }
            public boolean isModLoaded(String id) { return false; }
        });
    }

    @AfterEach
    void restoreGlobalConfig() throws Exception {
        providerField.set(null, originalProvider);
        instanceField.set(null, originalInstance);
    }

    private Path configPath() {
        return dir.resolve("atomchat/atomchat-client.json");
    }

    private AtomChatConfig loadStyle(String value) throws Exception {
        Files.createDirectories(configPath().getParent());
        Files.writeString(configPath(), "{\"uiStyle\":" + value
                + ",\"panelWidth\":612,\"accentColor\":123,\"animationEnabled\":false,"
                + "\"themeName\":\"mint\",\"pixelRadiusMigrated\":true}");
        AtomChatConfig.reload();
        AtomChatConfig config = AtomChatConfig.get();
        assertEquals(612.0F, config.panelWidth);
        assertEquals(123, config.accentColor);
        assertEquals(false, config.animationEnabled);
        assertEquals("mint", config.themeName);
        return config;
    }

    @Test
    void existingAndNullPreferencesKeepTheOriginalPresentation() {
        AtomChatConfig config = new AtomChatConfig();
        assertEquals(AtomChatConfig.UiStyle.ATOMCHAT, config.uiStyle);
        assertEquals(AtomChatConfig.MiuixNavigationStyle.ICON_AND_TEXT, config.miuixNavigationStyle);
        config.miuixNavigationStyle = null;
        assertEquals(AtomChatConfig.MiuixNavigationStyle.ICON_AND_TEXT, config.effectiveMiuixNavigationStyle());
        config.uiStyle = null;
        assertEquals(AtomChatConfig.UiStyle.ATOMCHAT, config.effectiveUiStyle());
    }

    @Test
    void styleLoadsCaseInsensitivelyAndPersistsCanonicalName() throws Exception {
        assertEquals(AtomChatConfig.UiStyle.MIUIX, loadStyle("\" mIuIx \"").effectiveUiStyle());
        assertEquals("MIUIX", JsonParser.parseString(Files.readString(configPath()))
                .getAsJsonObject().get("uiStyle").getAsString());
        AtomChatConfig.reload();
        assertEquals(AtomChatConfig.UiStyle.MIUIX, AtomChatConfig.get().effectiveUiStyle());
    }

    @Test
    void invalidStyleOnlyResetsItsOwnPreference() throws Exception {
        for (String value : new String[]{"\"unknown\"", "null", "{}", "[]", "[\"MIUIX\"]", "true", "7"}) {
            assertEquals(AtomChatConfig.UiStyle.ATOMCHAT, loadStyle(value).effectiveUiStyle(), value);
            assertEquals("ATOMCHAT", JsonParser.parseString(Files.readString(configPath()))
                    .getAsJsonObject().get("uiStyle").getAsString(), value);
        }
    }

    @Test
    void colorPresetsDoNotReplaceTheInterfaceChoice() {
        AtomChatConfig config = new AtomChatConfig();
        config.uiStyle = AtomChatConfig.UiStyle.MIUIX;
        config.miuixNavigationStyle = AtomChatConfig.MiuixNavigationStyle.FLOATING;
        for (String preset : new String[]{ThemeService.MODERN, ThemeService.FROSTED, "mint"}) {
            ThemeService.apply(config, preset);
            assertEquals(AtomChatConfig.UiStyle.MIUIX, config.effectiveUiStyle(), preset);
            assertEquals(AtomChatConfig.MiuixNavigationStyle.FLOATING, config.effectiveMiuixNavigationStyle(), preset);
        }
    }

    @Test
    void anOlderConfigKeepsItsSettingsAndAddsTheDefaultStyle() throws Exception {
        Files.createDirectories(configPath().getParent());
        Files.writeString(configPath(), "{\"panelWidth\":612,\"pixelRadiusMigrated\":true}");
        AtomChatConfig.reload();
        assertEquals(AtomChatConfig.UiStyle.ATOMCHAT, AtomChatConfig.get().effectiveUiStyle());
        assertEquals(612.0F, AtomChatConfig.get().panelWidth);
        assertEquals(AtomChatConfig.MiuixNavigationStyle.ICON_AND_TEXT, AtomChatConfig.get().effectiveMiuixNavigationStyle());
    }

    private AtomChatConfig loadNavigationStyle(String value) throws Exception {
        Files.createDirectories(configPath().getParent());
        Files.writeString(configPath(), "{\"miuixNavigationStyle\":" + value
                + ",\"uiStyle\":\"MIUIX\",\"panelWidth\":612,\"accentColor\":123,"
                + "\"themeName\":\"mint\",\"pixelRadiusMigrated\":true}");
        AtomChatConfig.reload();
        AtomChatConfig config = AtomChatConfig.get();
        assertEquals(AtomChatConfig.UiStyle.MIUIX, config.effectiveUiStyle());
        assertEquals(612.0F, config.panelWidth);
        assertEquals(123, config.accentColor);
        assertEquals("mint", config.themeName);
        return config;
    }

    @Test
    void navigationStyleLoadsCaseInsensitivelyAndAllChoicesPersist() throws Exception {
        for (AtomChatConfig.MiuixNavigationStyle style : AtomChatConfig.MiuixNavigationStyle.values()) {
            String value = "\" " + style.name().toLowerCase(java.util.Locale.ROOT) + " \"";
            assertEquals(style, loadNavigationStyle(value).effectiveMiuixNavigationStyle());
            assertEquals(style.name(), JsonParser.parseString(Files.readString(configPath()))
                    .getAsJsonObject().get("miuixNavigationStyle").getAsString());
            AtomChatConfig.reload();
            assertEquals(style, AtomChatConfig.get().effectiveMiuixNavigationStyle());
        }
    }

    @Test
    void malformedNavigationStyleOnlyResetsItsOwnPreference() throws Exception {
        for (String value : new String[]{"\"unknown\"", "null", "{}", "[]", "[\"FLOATING\"]", "true", "7"}) {
            assertEquals(AtomChatConfig.MiuixNavigationStyle.ICON_AND_TEXT,
                    loadNavigationStyle(value).effectiveMiuixNavigationStyle(), value);
            assertEquals("ICON_AND_TEXT", JsonParser.parseString(Files.readString(configPath()))
                    .getAsJsonObject().get("miuixNavigationStyle").getAsString(), value);
        }
    }

    @Test
    void savedStyleSurvivesReload() {
        AtomChatConfig config = new AtomChatConfig();
        config.uiStyle = AtomChatConfig.UiStyle.MIUIX;
        config.panelWidth = 612.0F;
        assertTrue(AtomChatConfig.saveChecked(config));
        AtomChatConfig.reload();
        assertEquals(AtomChatConfig.UiStyle.MIUIX, AtomChatConfig.get().effectiveUiStyle());
        assertEquals(612.0F, AtomChatConfig.get().panelWidth);
    }
}
