package com.atom.chat.ui.miuix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiuixPaletteTest {
    @Test
    void neutralTracksAndPressedFeedbackAdaptToLightAndDarkSurfaces() {
        int light = 0xFFF4F2ED;
        int dark = 0xFF20252D;
        assertTrue((MiuixPalette.neutralTrack(light) & 0xFF) < (light & 0xFF));
        assertTrue((MiuixPalette.neutralTrack(dark) & 0xFF) > (dark & 0xFF));
        assertNotEquals(light, MiuixPalette.pressed(light));
        assertNotEquals(dark, MiuixPalette.pressed(dark));
        assertEquals(0xFF000000, MiuixPalette.edge(light) | 0xFF000000);
        assertEquals(0xFFFFFFFF, MiuixPalette.edge(dark) | 0xFF000000);
    }

    @Test
    void disabledPreservesTheUsersHueAndScalesExistingAlpha() {
        int color = 0x8042A57D;
        assertEquals(color & 0xFFFFFF, MiuixPalette.disabled(color) & 0xFFFFFF);
        assertEquals(Math.round(128 * 0.38F), MiuixPalette.disabled(color) >>> 24);
        assertEquals(0, MiuixPalette.disabled(0x0042A57D) >>> 24);
    }
}
