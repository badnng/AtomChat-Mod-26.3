package com.atom.chat.render;

/** Physical world pixels needed by the panel, rounded to avoid resize allocations during springs. */
record NativeWorldRegion(int x, int y, int width, int height) {
    private static final int BLOCK = 64;

    static NativeWorldRegion covering(int viewportWidth, int viewportHeight,
                                      float x, float y, float width, float height) {
        if (viewportWidth <= 0 || viewportHeight <= 0 || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) return null;
        int left = Math.max(0, (int) Math.floor(x / BLOCK) * BLOCK);
        int top = Math.max(0, (int) Math.floor(y / BLOCK) * BLOCK);
        int right = Math.min(viewportWidth, (int) Math.ceil(((double) x + width) / BLOCK) * BLOCK);
        int bottom = Math.min(viewportHeight, (int) Math.ceil(((double) y + height) / BLOCK) * BLOCK);
        return right <= left || bottom <= top ? null : new NativeWorldRegion(left, top, right - left, bottom - top);
    }

    int sourceY(int viewportHeight) { return viewportHeight - y - height; }
    int byteCount() { return Math.multiplyExact(Math.multiplyExact(width, height), 4); }
}
