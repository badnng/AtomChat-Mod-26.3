package com.atom.chat.ui;

import com.atom.chat.render.SkiaDraw;
import com.atom.chat.ui.miuix.MiuixComponents;
import io.github.humbleui.skija.Canvas;

/**
 * Style-aware component surfaces. Layout and input bounds belong to callers;
 * the AtomChat branch delegates to the original drawing primitives unchanged.
 * Background-only drawing never clips descendants or creates an offscreen layer.
 */
public final class UiSurfaces {
    private UiSurfaces() {
    }

    public static void background(Canvas canvas, float x, float y, float w, float h,
                                  float radius, int fill) {
        if (MiuixComponents.isActive()) {
            MiuixComponents.surface(canvas, x, y, w, h, radius, fill);
        } else {
            SkiaDraw.drawRoundedRect(canvas, x, y, w, h, radius, fill);
        }
    }

    /** Header, composer and bottom navigation share their elevation tier. */
    public static void chrome(Canvas canvas, float x, float y, float w, float h, float radius) {
        if (MiuixComponents.isActive()) {
            MiuixComponents.chrome(canvas, x, y, w, h, radius);
        } else {
            SkiaDraw.drawChromeShadow(canvas, x, y, w, h, radius);
            SkiaDraw.drawRoundedRect(canvas, x, y, w, h, radius, UiTokens.cardFill());
        }
    }

    public static void shadow(Canvas canvas, float x, float y, float w, float h,
                              float radius, float blur, int color) {
        if (MiuixComponents.isActive()) {
            MiuixComponents.shadow(canvas, x, y, w, h, radius, color, blur, blur * 0.5F);
        } else {
            SkiaDraw.drawRoundedShadow(canvas, x, y, w, h, radius, blur, color);
        }
    }

    /** The caller pairs this clip with its own save/restore. */
    public static void clip(Canvas canvas, float x, float y, float w, float h, float radius) {
        if (MiuixComponents.isActive()) {
            MiuixComponents.clip(canvas, x, y, w, h, radius);
        } else {
            SkiaDraw.clip(canvas, x, y, w, h, radius);
        }
    }
}
