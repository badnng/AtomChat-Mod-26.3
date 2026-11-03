package com.atom.chat.ui.miuix;

import com.atom.chat.render.SkiaDraw;
import com.atom.chat.ui.UiTokens;
import io.github.humbleui.skija.Canvas;

/** Optional control painting; callers retain all layout, animation and input state. */
public final class MiuixControls {
    private static final float SWITCH_THUMB_RATIO = 0.84F;
    private static final float SLIDER_TRACK_RATIO = 0.66F;

    private MiuixControls() {}

    /** Returns false without touching the canvas when the classic style is selected. */
    public static boolean toggle(Canvas canvas, float x, float y, float progress,
                                 int accent, boolean enabled) {
        if (!MiuixComponents.isActive()) return false;
        float p = clamp(progress);
        int track = SkiaDraw.lerpColor(MiuixPalette.neutralTrack(), accent, p);
        int thumb = MiuixPalette.thumb();
        if (!enabled) {
            track = MiuixPalette.disabled(track);
            thumb = MiuixPalette.disabled(thumb);
        }
        MiuixComponents.surface(canvas, x, y, UiTokens.SWITCH_W, UiTokens.SWITCH_H,
                UiTokens.radius(UiTokens.SWITCH_H / (2.0F * UiTokens.SCALE)), track);
        float diameter = UiTokens.SWITCH_KNOB * SWITCH_THUMB_RATIO;
        float inset = (UiTokens.SWITCH_H - diameter) / 2.0F;
        float thumbX = x + inset + (UiTokens.SWITCH_W - diameter - inset * 2.0F) * p;
        SkiaDraw.drawRoundedRect(canvas, thumbX, y + inset, diameter, diameter,
                UiTokens.handleRadius(diameter), thumb);
        return true;
    }

    /** The supplied track is the existing drag geometry, including its original centerline. */
    public static boolean slider(Canvas canvas, float x, float y, float width, float height,
                                 float progress, int accent) {
        if (!MiuixComponents.isActive()) return false;
        float p = clamp(progress);
        float centerY = y + height / 2.0F;
        float trackHeight = UiTokens.SLIDER_KNOB * SLIDER_TRACK_RATIO;
        float top = centerY - trackHeight / 2.0F;
        float radius = UiTokens.handleRadius(trackHeight);
        MiuixComponents.surface(canvas, x, top, width, trackHeight, radius,
                MiuixPalette.neutralTrack());
        canvas.save();
        try {
            MiuixComponents.clip(canvas, x, top, width, trackHeight, radius);
            if (p > 0.0F) {
                MiuixComponents.surface(canvas, x, top, width * p, trackHeight, radius, accent);
            }
        } finally {
            canvas.restore();
        }
        float diameter = UiTokens.SLIDER_KNOB;
        float thumbX = x + width * p - diameter / 2.0F;
        float thumbY = centerY - diameter / 2.0F;
        float thumbRadius = UiTokens.handleRadius(diameter);
        SkiaDraw.drawRoundedRect(canvas, thumbX, thumbY,
                diameter, diameter, thumbRadius, MiuixPalette.thumb());
        SkiaDraw.drawEdgeHighlight(canvas, thumbX, thumbY, diameter, diameter,
                thumbRadius, UiTokens.MIUIX_BORDER_WIDTH, accent);
        return true;
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
