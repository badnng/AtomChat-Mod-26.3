package com.atom.chat.ui.miuix;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.render.SkiaDraw;
import com.atom.chat.theme.ThemeService;
import com.atom.chat.ui.UiTokens;

/** Semantic MIUIX colours resolved from the user's live AtomChat palette. */
public final class MiuixPalette {
    private MiuixPalette() {
    }

    public static int surface() {
        return UiTokens.cardFill();
    }

    public static int primary() {
        return AtomChatConfig.get().accentColor;
    }

    public static int onPrimary() {
        return UiTokens.onAccent(primary());
    }

    public static int onSurface() {
        return AtomChatConfig.get().textPrimaryColor;
    }

    public static int neutralTrack() {
        return neutralTrack(UiTokens.cardCutout());
    }

    /** Opaque neutral so overlapping track segments never acquire seams. */
    public static int neutralTrack(int base) {
        return SkiaDraw.lerpColor(base, neutralInk(base), 0.15F);
    }

    public static int thumb() {
        return SkiaDraw.lerpColor(UiTokens.cardCutout(), 0xFFFFFFFF, 0.96F);
    }

    public static int hover(int base, float weight) {
        return UiTokens.cardHover(primary(), base, Math.max(0.0F, Math.min(1.0F, weight)) * 0.55F);
    }

    public static int pressed(int base) {
        return SkiaDraw.lerpColor(base, neutralInk(base), 0.10F);
    }

    public static int disabled(int color) {
        return UiTokens.withAlpha(color, (color >>> 24) * 0.38F);
    }

    public static int edge(int base) {
        return UiTokens.withAlpha(neutralInk(base), 18.0F);
    }

    public static int shadow() {
        return ThemeService.colorIsLight(UiTokens.cardCutout()) ? 0x14000000 : 0x22000000;
    }

    private static int neutralInk(int base) {
        return ThemeService.colorIsLight(base) ? 0xFF000000 : 0xFFFFFFFF;
    }
}
