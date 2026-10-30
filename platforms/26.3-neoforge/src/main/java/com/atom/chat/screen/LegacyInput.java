package com.atom.chat.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

/** Translates SDL input into the mapping-neutral UI controller's existing key protocol. */
final class LegacyInput {
    private LegacyInput() {}

    static int key(KeyEvent event) {
        int key = event.key();
        // Letter shortcuts follow the keyboard layout, like vanilla EditBox.
        if (event.keycode() >= 'a' && event.keycode() <= 'z') return Character.toUpperCase(event.keycode());
        if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) return 'A' + key - InputConstants.KEY_A;
        if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9) return '1' + key - InputConstants.KEY_1;
        if (key >= InputConstants.KEY_F1 && key <= InputConstants.KEY_F12) return 290 + key - InputConstants.KEY_F1;
        return switch (key) {
            case InputConstants.KEY_0 -> '0';
            case InputConstants.KEY_ESCAPE -> 256;
            case InputConstants.KEY_RETURN -> 257;
            case InputConstants.KEY_TAB -> 258;
            case InputConstants.KEY_BACKSPACE -> 259;
            case InputConstants.KEY_INSERT -> 260;
            case InputConstants.KEY_DELETE -> 261;
            case InputConstants.KEY_RIGHT -> 262;
            case InputConstants.KEY_LEFT -> 263;
            case InputConstants.KEY_DOWN -> 264;
            case InputConstants.KEY_UP -> 265;
            case InputConstants.KEY_PAGEUP -> 266;
            case InputConstants.KEY_PAGEDOWN -> 267;
            case InputConstants.KEY_HOME -> 268;
            case InputConstants.KEY_END -> 269;
            case InputConstants.KEY_NUMPADENTER -> 335;
            case InputConstants.KEY_SPACE -> 32;
            default -> event.keycode();
        };
    }

    static int mouseButton(int button) {
        return switch (button) {
            case InputConstants.MOUSE_BUTTON_LEFT -> 0;
            case InputConstants.MOUSE_BUTTON_RIGHT -> 1;
            case InputConstants.MOUSE_BUTTON_MIDDLE -> 2;
            default -> button - 1;
        };
    }

    static int nativeMouseButton(int button) {
        return switch (button) {
            case 0 -> InputConstants.MOUSE_BUTTON_LEFT;
            case 1 -> InputConstants.MOUSE_BUTTON_RIGHT;
            case 2 -> InputConstants.MOUSE_BUTTON_MIDDLE;
            default -> button + 1;
        };
    }

    static int modifiers(KeyEvent event) {
        int modifiers = event.modifiers();
        return ((modifiers & InputConstants.MOD_SHIFT) != 0 ? 1 : 0)
                | ((modifiers & InputConstants.MOD_CONTROL) != 0 ? 2 : 0)
                | ((modifiers & InputConstants.MOD_ALT) != 0 ? 4 : 0)
                | ((modifiers & InputConstants.MOD_SUPER) != 0 ? 8 : 0);
    }
}
