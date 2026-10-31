package com.atom.chat.render;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.ui.PanelOpenMotion;

/**
 * Fixed framebuffer-space raster bounds for the panel across its open and close
 * animation. The full-size panel in the progress-zero SLIDE fade layer contains
 * the scaled ZOOM poses too; the extra slack keeps the bezel and its shadow.
 */
public record PanelRasterBounds(int x, int y, int width, int height) {
    private static final float SHADOW_SLACK = 64.0F;

    /**
     * Builds a stable pixel-aligned rectangle for all panel animation poses.
     * The supplied panel geometry is in UI coordinates; returned bounds are
     * clipped to the physical framebuffer viewport.
     */
    public static PanelRasterBounds of(int viewportWidth, int viewportHeight, float density,
                                       float panelX, float panelY, float panelWidth, float panelHeight) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }
        if (!Float.isFinite(density) || density <= 0.0F
                || !Float.isFinite(panelX) || !Float.isFinite(panelY)
                || !Float.isFinite(panelWidth) || panelWidth <= 0.0F
                || !Float.isFinite(panelHeight) || panelHeight <= 0.0F) {
            throw new IllegalArgumentException("Density and panel geometry must be finite and positive where applicable");
        }

        PanelOpenMotion.LayerRect swept = PanelOpenMotion.fadeLayer(0.0F,
                AtomChatConfig.PageNavStyle.SLIDE, panelX, panelY, panelWidth, panelHeight, SHADOW_SLACK);
        double leftPx = (double) swept.x() * density;
        double topPx = (double) swept.y() * density;
        double rightPx = ((double) swept.x() + swept.w()) * density;
        double bottomPx = ((double) swept.y() + swept.h()) * density;

        int left = floorClamped(leftPx, viewportWidth);
        int top = floorClamped(topPx, viewportHeight);
        int right = ceilClamped(rightPx, viewportWidth);
        int bottom = ceilClamped(bottomPx, viewportHeight);

        // A fully off-screen panel still needs a legal, non-empty texture rect.
        if (right <= left) {
            left = Math.min(left, viewportWidth - 1);
            right = left + 1;
        }
        if (bottom <= top) {
            top = Math.min(top, viewportHeight - 1);
            bottom = top + 1;
        }
        return new PanelRasterBounds(left, top, right - left, bottom - top);
    }

    private static int floorClamped(double value, int limit) {
        if (value <= 0.0D) {
            return 0;
        }
        if (value >= limit) {
            return limit;
        }
        return (int) Math.floor(value);
    }

    private static int ceilClamped(double value, int limit) {
        if (value <= 0.0D) {
            return 0;
        }
        if (value >= limit) {
            return limit;
        }
        return (int) Math.ceil(value);
    }
}
