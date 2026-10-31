package com.atom.chat.render;

import org.junit.jupiter.api.Test;
import org.lwjgl.sdl.SDLEvents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowEventRoutingTest {
    @Test
    void privateWindowCannotResizeRefocusOrCloseTheGameWindow() {
        for (int type = SDLEvents.SDL_EVENT_WINDOW_FIRST; type <= SDLEvents.SDL_EVENT_WINDOW_LAST; type++) {
            assertFalse(WindowEventRouting.accepts(type, 2, 1));
            assertTrue(WindowEventRouting.accepts(type, 1, 1));
        }
    }

    @Test
    void globalQuitDisplayAndInputEventsKeepTheirExistingRouting() {
        assertTrue(WindowEventRouting.accepts(SDLEvents.SDL_EVENT_QUIT, 0, 1));
        assertTrue(WindowEventRouting.accepts(SDLEvents.SDL_EVENT_DISPLAY_ADDED, 2, 1));
        assertTrue(WindowEventRouting.accepts(SDLEvents.SDL_EVENT_KEY_DOWN, 1, 1));
    }
}
