package com.atom.chat.ui.miuix;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.ui.UiTokens;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.FilterBlurMode;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Render-thread-only primitives sharing one contour for fill, clip, stroke and
 * shadow. The scratch path/paints live for the renderer's lifetime; the blur
 * cache is bounded and closes evicted native handles explicitly.
 */
public final class MiuixComponents {
    private static final Path SCRATCH = new Path();
    private static final Paint PAINT = new Paint().setAntiAlias(true);
    private static final Paint SHADOW_PAINT = new Paint().setAntiAlias(true);
    private static final int MAX_BLURS = 16;
    private static final Map<Float, MaskFilter> BLURS = new LinkedHashMap<>(MAX_BLURS, 0.75F, true);

    private MiuixComponents() {
    }

    public static boolean isActive() {
        return AtomChatConfig.get().effectiveUiStyle() == AtomChatConfig.UiStyle.MIUIX;
    }

    public static void surface(Canvas canvas, float x, float y, float w, float h, float radius, int fill) {
        canvas.drawPath(contour(x, y, w, h, radius), PAINT.setColor(fill).setMode(PaintMode.FILL));
    }

    /** Installs a clip only; the caller owns its surrounding save/restore. */
    public static void clip(Canvas canvas, float x, float y, float w, float h, float radius) {
        canvas.clipPath(contour(x, y, w, h, radius), ClipMode.INTERSECT, true);
    }

    /** The border stays inside the exact filled/clip contour, including at tight corners. */
    public static void border(Canvas canvas, float x, float y, float w, float h, float radius,
                              float strokeWidth, int color) {
        if (!(strokeWidth > 0.0F) || !Float.isFinite(strokeWidth)) {
            return;
        }
        Path shape = contour(x, y, w, h, radius);
        canvas.save();
        try {
            canvas.clipPath(shape, ClipMode.INTERSECT, true);
            canvas.drawPath(shape, PAINT.setColor(color).setMode(PaintMode.STROKE)
                    .setStrokeWidth(strokeWidth * 2.0F));
        } finally {
            canvas.restore();
        }
    }

    public static void shadow(Canvas canvas, float x, float y, float w, float h, float radius,
                              int color, float blur, float dy) {
        if (!Float.isFinite(blur) || blur <= 0.0F || !Float.isFinite(dy)) {
            return;
        }
        // Quarter-pixel quantisation avoids distinct filters for subpixel animation noise.
        float sigma = Math.max(0.25F, Math.round(blur * 4.0F) / 4.0F);
        MaskFilter filter = BLURS.get(sigma);
        if (filter == null) {
            if (BLURS.size() >= MAX_BLURS) {
                SHADOW_PAINT.setMaskFilter(null);
                var oldest = BLURS.entrySet().iterator();
                MaskFilter evicted = oldest.next().getValue();
                oldest.remove();
                evicted.close();
            }
            filter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma);
            BLURS.put(sigma, filter);
        }
        canvas.drawPath(contour(x, y + dy, w, h, radius),
                SHADOW_PAINT.setColor(color).setMode(PaintMode.FILL).setMaskFilter(filter));
    }

    public static void chrome(Canvas canvas, float x, float y, float w, float h, float radius) {
        shadow(canvas, x, y, w, h, radius, MiuixPalette.shadow(),
                UiTokens.MIUIX_SHADOW_BLUR, UiTokens.MIUIX_SHADOW_Y);
        surface(canvas, x, y, w, h, radius, MiuixPalette.surface());
    }

    public static void button(Canvas canvas, float x, float y, float w, float h, float radius,
                              boolean primary, boolean hovered, boolean pressed, boolean disabled) {
        int fill = primary ? MiuixPalette.primary() : MiuixPalette.neutralTrack();
        if (pressed && !disabled) {
            fill = MiuixPalette.pressed(fill);
        }
        surface(canvas, x, y, w, h, radius, disabled ? MiuixPalette.disabled(fill) : fill);
        if (hovered && !disabled && !pressed) {
            surface(canvas, x, y, w, h, radius, MiuixPalette.hover(fill, 1.0F));
        }
    }

    private static Path contour(float x, float y, float w, float h, float radius) {
        return MiuixShapes.continuousRect(SCRATCH, x, y, w, h, radius);
    }

    static int cachedBlurCount() {
        return BLURS.size();
    }
}
