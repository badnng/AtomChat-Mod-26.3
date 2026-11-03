package com.atom.chat.automation;

import com.atom.chat.config.AtomChatConfig;
import com.atom.chat.screen.AtomChatScreen;
import com.atom.chat.screen.ScreenMetrics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pointer-level resize probe followed by the game's normal retained GPU redraw. */
final class AutomationMiuixResize {
    private static final Map<String, Float> originals = new LinkedHashMap<>();
    private static String active;
    private static float expected;
    private static boolean wasGpu;
    private static long scrollAt;

    private AutomationMiuixResize() { }

    static void begin() {
        originals.clear();
        for (String id : new String[]{"width", "scale", "contentscale"}) originals.put(id, value(id));
        scrollAt = 0;
    }

    static boolean reveal(AtomChatScreen screen, String id) {
        float dp = guiDp();
        var window = Minecraft.getInstance().getWindow();
        float bottom = (ScreenMetrics.panelY(window) + ScreenMetrics.panelHeight(window)) * dp;
        float[] row = screen.nativeMiuixBounds("setting-" + id);
        float[] bar = screen.nativeMiuixBounds("miuix-top-app-bar");
        // Semantic bounds are clipped to the panel. A clipped bottom makes
        // row.bottom - 32dp land above the actual slider track after zooming.
        if (row != null && bar != null && row[3] < bottom - 4 * dp
                && row[3] - row[1] >= 90 * dp && row[3] - 32 * dp > bar[3] + 4 * dp)
            return true;
        long now = System.nanoTime();
        if (now - scrollAt > 200_000_000L) {
            double direction = -2;
            if (row != null && bar != null && row[1] < bar[3] + 4 * dp) direction = 2;
            if (row == null) {
                var order = java.util.List.of("opacity", "width", "scale", "contentscale", "corner_radius");
                int targetIndex = order.indexOf(id);
                for (int index = 0; index < order.size(); index++) {
                    float[] visible = screen.nativeMiuixBounds("setting-" + order.get(index));
                    if (visible != null && bar != null && visible[3] > bar[3]) {
                        direction = targetIndex < index ? 2 : -2;
                        break;
                    }
                }
            }
            // The panel is left anchored; the window center can lie outside it.
            double x = (ScreenMetrics.panelX() + ScreenMetrics.panelWidth(window) / 2) * dp;
            double y = (ScreenMetrics.panelY(window) + ScreenMetrics.panelHeight(window) * .6) * dp;
            screen.mouseScrolled(x, y, 0, direction);
            scrollAt = now;
        }
        return false;
    }

    static void drag(AtomChatScreen screen, String id, boolean maximum) throws Exception {
        active = id;
        expected = id.equals("width") ? (maximum ? 600 : 400) : maximum ? 1.5F : id.equals("scale") ? .75F : .8F;
        wasGpu = Boolean.TRUE.equals(snapshot(screen).get("gpuActive"));
        float initial = value(id);
        float[] row = screen.nativeMiuixBounds("setting-" + id);
        if (row == null) throw new AssertionError("Missing resize slider " + id);
        float dp = guiDp();
        float x = (row[0] + row[2]) / 2;
        float y = row[3] - 32 * dp;
        float target = maximum ? row[2] - 8 * dp : row[0] + 8 * dp;
        screen.mouseClicked(event(x, y), false);
        // One input batch keeps the real cursor poll from replacing these
        // synthetic coordinates. The regular render loop verifies the result.
        for (int step = 1; step <= 12; step++) {
            float next = x + (target - x) * step / 12;
            screen.mouseDragged(event(next, y), (target - x) / 12, 0);
            if (value(id) != initial) throw new AssertionError(id + " changed its own viewport before release");
        }
        screen.mouseReleased(event(target, y));
    }

    static boolean settled(AtomChatScreen screen) throws Exception {
        if (Math.abs(value(active) - expected) > .001F) return false;
        if (screen.nativeMiuixBounds("miuix-top-app-bar") == null) return false;
        var state = snapshot(screen);
        if (wasGpu && !Boolean.TRUE.equals(state.get("gpuActive")))
            throw new AssertionError("Panel resize silently fell back from GPU: " + state);
        return true;
    }

    static void restore() {
        var config = AtomChatConfig.get();
        config.panelWidth = originals.get("width");
        config.uiScale = originals.get("scale");
        config.contentScale = originals.get("contentscale");
        if (!AtomChatConfig.saveChecked(config)) throw new AssertionError("Could not restore resize fixture");
    }

    private static MouseButtonEvent event(float x, float y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0));
    }

    private static float value(String id) {
        var config = AtomChatConfig.get();
        return switch (id) {
            case "width" -> config.panelWidth;
            case "scale" -> config.uiScale;
            case "contentscale" -> config.contentScale;
            default -> throw new IllegalArgumentException(id);
        };
    }

    private static float guiDp() {
        var window = Minecraft.getInstance().getWindow();
        return ScreenMetrics.uiDensity(window) / (float) window.getGuiScale();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> snapshot(AtomChatScreen screen) throws Exception {
        var field = AtomChatScreen.class.getDeclaredField("miuixBridge");
        field.setAccessible(true);
        Object bridge = field.get(screen);
        var method = bridge.getClass().getDeclaredMethod("performanceSnapshot");
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(bridge);
    }
}
