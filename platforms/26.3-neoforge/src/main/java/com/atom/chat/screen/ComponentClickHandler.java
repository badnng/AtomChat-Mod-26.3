package com.atom.chat.screen;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;

import java.util.function.Consumer;

/** Preserves vanilla chat's insertion priority while delegating every click event to its game handler. */
final class ComponentClickHandler {
    private ComponentClickHandler() {}

    static boolean handle(Style style, boolean allowInsertions, Consumer<String> insert,
                          Consumer<ClickEvent> gameClick) {
        if (style == null) return false;
        if (allowInsertions) {
            if (style.getInsertion() != null) insert.accept(style.getInsertion());
            return false;
        }
        if (style.getClickEvent() != null) {
            gameClick.accept(style.getClickEvent());
            return true;
        }
        return false;
    }
}
