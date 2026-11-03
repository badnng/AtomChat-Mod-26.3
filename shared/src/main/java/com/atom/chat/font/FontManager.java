package com.atom.chat.font;

import com.atom.chat.AtomChat;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.FontVariation;
import io.github.humbleui.skija.FontVariationAxis;
import io.github.humbleui.skija.Typeface;

import java.io.InputStream;
import java.util.Locale;

/**
 * Loads and caches Skia fonts. Prefers a bundled open-source CJK font when present,
 * otherwise falls back to a system font so development can proceed without assets.
 */
public final class FontManager {
    /** Drop a licensed font file here (e.g. Noto Sans SC) to bundle it. */
    private static final String BUNDLED_FONT = "/assets/atomchat/font/bundled.otf";
    private static final float MEDIUM_WEIGHT = 500.0F;
    private static final float BOLD_WEIGHT = 700.0F;
    /** Faux-bold for thin system fallback fonts; a real bundled font makes this redundant. */
    private static boolean usingFallbackTypeface;
    private static Typeface defaultTypeface;
    private static boolean boldLookupDone;
    private static Typeface boldTypeface;
    private static final java.util.Map<String, Font> CACHE = new java.util.HashMap<>();
    private static final java.util.Map<String, Font> BOLD_CACHE = new java.util.HashMap<>();

    private FontManager() {
    }

    public static Typeface getDefaultTypeface() {
        if (defaultTypeface != null) {
            return defaultTypeface;
        }

        // Bundled font first.
        try (InputStream in = FontManager.class.getResourceAsStream(BUNDLED_FONT)) {
            if (in != null) {
                defaultTypeface = mediumWeight(Typeface.makeFromData(Data.makeFromBytes(in.readAllBytes())));
                usingFallbackTypeface = false;
                AtomChat.LOGGER.info(com.atom.chat.diagnostics.AtomChatLog.message("resource", "Loaded bundled font {}"), BUNDLED_FONT);
                return defaultTypeface;
            }
        } catch (Exception e) {
            AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("resource", "Failed to load bundled font, falling back to system font"), e);
        }

        // System fallbacks. Windows first (unchanged), then the CJK fonts Linux
        // distributions ship, so a dev run without the bundled asset still gets
        // a readable face instead of the process default.
        usingFallbackTypeface = true;
        String[] candidates = {"Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Noto Sans SC",
                "WenQuanYi Micro Hei", "WenQuanYi Zen Hei", "Arial", "DejaVu Sans"};
        for (String name : candidates) {
            Typeface tf = Typeface.makeFromName(name, FontStyle.NORMAL);
            if (tf != null && !tf.getFamilyName().toLowerCase(Locale.ROOT).contains("arial") || tf != null && tf.getFamilyName().toLowerCase(Locale.ROOT).contains("yahei")) {
                defaultTypeface = tf;
                AtomChat.LOGGER.info(com.atom.chat.diagnostics.AtomChatLog.message("resource", "Using system font {}"), tf.getFamilyName());
                return defaultTypeface;
            }
            if (tf != null) {
                defaultTypeface = tf;
                AtomChat.LOGGER.info(com.atom.chat.diagnostics.AtomChatLog.message("resource", "Using system font {}"), tf.getFamilyName());
                return defaultTypeface;
            }
        }
        defaultTypeface = Typeface.makeDefault();
        return defaultTypeface;
    }

    /** Variable fonts are cloned at Medium (wght 500) so text is not too thin. */
    private static Typeface mediumWeight(Typeface face) {
        try {
            Typeface medium = cloneAtWeight(face, MEDIUM_WEIGHT);
            return medium != null ? medium : face;
        } catch (Throwable t) {
            AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("resource", "Variable font weight clone failed, using default instance"), t);
        }
        return face;
    }

    /** Return a real variable-weight face, or {@code null} when this typeface has no wght axis. */
    private static Typeface cloneAtWeight(Typeface face, float requestedWeight) {
        for (FontVariationAxis axis : face.getVariationAxes()) {
            if ("wght".equals(axis.getTag())) {
                float weight = Math.max(axis.getMinValue(), Math.min(axis.getMaxValue(), requestedWeight));
                return face.makeClone(new FontVariation("wght", weight));
            }
        }
        return null;
    }

    public static Font font(float size) {
        String key = size + "px";
        Font cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Font font = new Font(getDefaultTypeface(), size);
        if (usingFallbackTypeface) {
            font.setEmboldened(true);
        }
        CACHE.put(key, font);
        return font;
    }

    /** A cached true-bold variable face, with faux-bold reserved for fixed system fallbacks. */
    public static Font boldFont(float size) {
        String key = size + "px-bold";
        Font cached = BOLD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Typeface bold = getBoldTypeface();
        if (bold != null) {
            Font font = new Font(bold, size);
            BOLD_CACHE.put(key, font);
            return font;
        }
        Font font = new Font(getDefaultTypeface(), size);
        font.setEmboldened(true);
        BOLD_CACHE.put(key, font);
        return font;
    }

    /** Clone the default family at bold weight, or return {@code null} for a fixed system face. */
    private static Typeface getBoldTypeface() {
        if (boldLookupDone) {
            return boldTypeface;
        }
        boldLookupDone = true;
        try {
            boldTypeface = cloneAtWeight(getDefaultTypeface(), BOLD_WEIGHT);
            if (boldTypeface != null) {
                AtomChat.LOGGER.info(com.atom.chat.diagnostics.AtomChatLog.message("resource", "Using variable font weight {} for bold text"), BOLD_WEIGHT);
            }
        } catch (Throwable t) {
            AtomChat.LOGGER.warn(com.atom.chat.diagnostics.AtomChatLog.message("resource", "Variable bold clone failed, falling back to faux bold"), t);
        }
        return boldTypeface;
    }
}
