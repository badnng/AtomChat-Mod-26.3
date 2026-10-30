package com.atom.chat.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyInputTest {
    @Test
    void mouseActionsPreserveLeftRightAndMiddleAcrossSdl() {
        assertEquals(0, LegacyInput.mouseButton(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals(1, LegacyInput.mouseButton(InputConstants.MOUSE_BUTTON_RIGHT));
        assertEquals(2, LegacyInput.mouseButton(InputConstants.MOUSE_BUTTON_MIDDLE));
        assertEquals(InputConstants.MOUSE_BUTTON_LEFT, LegacyInput.nativeMouseButton(0));
        assertEquals(InputConstants.MOUSE_BUTTON_RIGHT, LegacyInput.nativeMouseButton(1));
    }

    @Test
    void enterAndEscapeReachTheExistingNavigationProtocol() {
        assertEquals(257, LegacyInput.key(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN, 0)));
        assertEquals(335, LegacyInput.key(new KeyEvent(InputConstants.KEY_NUMPADENTER, InputConstants.KEYCODE_NUMPADENTER, 0)));
        assertEquals(256, LegacyInput.key(new KeyEvent(InputConstants.KEY_ESCAPE, 27, 0)));
    }

    @Test
    void composerNavigationUsesTheExistingArrowProtocol() {
        assertEquals(265, LegacyInput.key(new KeyEvent(InputConstants.KEY_UP, InputConstants.KEYCODE_UP, 0)));
        assertEquals(264, LegacyInput.key(new KeyEvent(InputConstants.KEY_DOWN, InputConstants.KEYCODE_DOWN, 0)));
        assertEquals(259, LegacyInput.key(new KeyEvent(InputConstants.KEY_BACKSPACE, InputConstants.KEYCODE_BACKSPACE, 0)));
    }

    @Test
    void clipboardShortcutsFollowTheLogicalKeyboardLayout() {
        assertEquals('V', LegacyInput.key(new KeyEvent(InputConstants.KEY_C, InputConstants.KEYCODE_V, InputConstants.MOD_CONTROL)));
    }

    @Test
    void eitherSideOfSdlModifiersReachesTheUiProtocol() {
        assertEquals(3, LegacyInput.modifiers(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN,
                InputConstants.MOD_CONTROL | InputConstants.MOD_SHIFT)));
        assertEquals(12, LegacyInput.modifiers(new KeyEvent(InputConstants.KEY_RETURN, InputConstants.KEYCODE_RETURN,
                InputConstants.MOD_ALT | InputConstants.MOD_SUPER)));
    }
}
