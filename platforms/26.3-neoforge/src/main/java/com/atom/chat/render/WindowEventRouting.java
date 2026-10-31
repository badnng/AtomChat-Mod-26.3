package com.atom.chat.render;

import org.lwjgl.sdl.SDLEvents;

/** SDL window events belong to their originating window, not the game globally. */
public final class WindowEventRouting {
    private WindowEventRouting() { }

    public static boolean accepts(int type, int eventWindow, int gameWindow) {
        return type < SDLEvents.SDL_EVENT_WINDOW_FIRST || type > SDLEvents.SDL_EVENT_WINDOW_LAST
                || eventWindow == gameWindow;
    }
}
