package com.atom.chat.ui.miuix;

import io.github.humbleui.skija.Path;

/**
 * A Skija continuous-corner approximation inspired by MIUIX surfaces, not a
 * copy of the upstream Compose path. Two cubics per corner keep the tangent
 * and zero curvature where the corner meets a straight edge. Their diagonal
 * join approximates a fourth-order superellipse.
 */
public final class MiuixShapes {
    private static final float DIAGONAL = 0.8408964F;
    private static final float SHOULDER = 2.0F * DIAGONAL - 1.0F;
    private static final float LEAD = 0.4F;

    private MiuixShapes() {
    }

    /** Rewinds the caller-owned path, retaining native storage for later frames. */
    public static Path continuousRect(Path path, float x, float y, float width, float height,
                                      float radius) {
        path.rewind();
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width)
                || !Float.isFinite(height) || width <= 0.0F || height <= 0.0F
                || !Float.isFinite(x + width) || !Float.isFinite(y + height)) {
            return path;
        }
        float r = Float.isFinite(radius)
                ? Math.max(0.0F, Math.min(radius, Math.min(width, height) * 0.5F)) : 0.0F;
        float right = x + width;
        float bottom = y + height;
        if (r == 0.0F) {
            return path.moveTo(x, y).lineTo(right, y).lineTo(right, bottom)
                    .lineTo(x, bottom).closePath();
        }
        float a = r * LEAD;
        float b = r * SHOULDER;
        float d = r * DIAGONAL;
        path.moveTo(x + r, y).lineTo(right - r, y);
        path.cubicTo(right - r + a, y, right - r + b, y, right - r + d, y + r - d);
        path.cubicTo(right, y + r - b, right, y + r - a, right, y + r);
        path.lineTo(right, bottom - r);
        path.cubicTo(right, bottom - r + a, right, bottom - r + b,
                right - r + d, bottom - r + d);
        path.cubicTo(right - r + b, bottom, right - r + a, bottom, right - r, bottom);
        path.lineTo(x + r, bottom);
        path.cubicTo(x + r - a, bottom, x + r - b, bottom, x + r - d, bottom - r + d);
        path.cubicTo(x, bottom - r + b, x, bottom - r + a, x, bottom - r);
        path.lineTo(x, y + r);
        path.cubicTo(x, y + r - a, x, y + r - b, x + r - d, y + r - d);
        path.cubicTo(x + r - b, y, x + r - a, y, x + r, y);
        // Path.close() releases the native handle; only closePath closes a contour.
        return path.closePath();
    }
}
