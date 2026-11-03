package com.atom.chat.ui.miuix;

import io.github.humbleui.skija.Path;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiuixShapesTest {
    @Test
    void zeroRadiusKeepsSquareCornersAndCallerOwnedHandle() {
        try (Path path = new Path()) {
            assertSame(path, MiuixShapes.continuousRect(path, 10, 20, 80, 40, 0));
            assertBounds(path, 10, 20, 80, 40);
            assertTrue(path.contains(10.5F, 20.5F));
            assertTrue(path.isLastContourClosed());
            MiuixShapes.continuousRect(path, 10, 20, 80, 40, 15);
            assertFalse(path.contains(10.5F, 20.5F));
            assertTrue(path.contains(50, 40));
        }
    }

    @Test
    void radiusClampsToHalfTheShortSideAndNeverEscapesBounds() {
        try (Path clamped = new Path(); Path oversized = new Path()) {
            MiuixShapes.continuousRect(clamped, -10, -20, 80, 40, 20);
            MiuixShapes.continuousRect(oversized, -10, -20, 80, 40, Float.MAX_VALUE);
            assertBounds(oversized, -10, -20, 80, 40);
            assertEquals(clamped, oversized);
            assertTrue(oversized.isFinite());
            assertTrue(oversized.isLastContourClosed());
            for (var point : oversized.getPoints()) {
                assertTrue(point.getX() >= -10 && point.getX() <= 70);
                assertTrue(point.getY() >= -20 && point.getY() <= 20);
            }
        }
    }

    @Test
    void emptyOrInvalidDimensionsClearThePreviousContour() {
        try (Path path = new Path()) {
            for (float width : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
                MiuixShapes.continuousRect(path, 0, 0, 80, 40, 15);
                MiuixShapes.continuousRect(path, 0, 0, width, 40, 15);
                assertTrue(path.isEmpty());
            }
            MiuixShapes.continuousRect(path, 0, 0, 80, 40, Float.NaN);
            assertTrue(path.isFinite());
            assertTrue(path.contains(0.5F, 0.5F));
        }
    }

    private static void assertBounds(Path path, float x, float y, float w, float h) {
        Rect bounds = path.computeTightBounds();
        assertEquals(x, bounds.getLeft(), 0.0001F);
        assertEquals(y, bounds.getTop(), 0.0001F);
        assertEquals(w, bounds.getWidth(), 0.0001F);
        assertEquals(h, bounds.getHeight(), 0.0001F);
    }
}
