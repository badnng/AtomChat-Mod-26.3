package com.atom.chat.screen;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ComponentClickHandlerTest {
    @Test
    void gameAndOrdinaryClickEventsReachTheDispatcherUnchanged() {
        List<ClickEvent> events = List.of(
                new ClickEvent.RunCommand("/say clicked"),
                new ClickEvent.Custom(Identifier.fromNamespaceAndPath("atomchat", "test_click"),
                        Optional.of(StringTag.valueOf("custom payload"))),
                new ClickEvent.SuggestCommand("/msg Alice "),
                new ClickEvent.CopyToClipboard("copied text"),
                new ClickEvent.OpenUrl(URI.create("https://example.com/")),
                new ClickEvent.OpenFile("example.txt"));
        for (ClickEvent event : events) {
            List<ClickEvent> dispatched = new ArrayList<>();
            List<String> inserted = new ArrayList<>();
            assertTrue(ComponentClickHandler.handle(Style.EMPTY.withClickEvent(event).withInsertion("insert"),
                    false, inserted::add, dispatched::add));
            assertEquals(1, dispatched.size());
            assertSame(event, dispatched.getFirst(), "the game dispatcher must receive the original event");
            assertTrue(inserted.isEmpty(), "ordinary clicks must not insert Shift text");
        }
    }

    @Test
    void shiftInsertsTheExactTextAndNeverRunsTheAttachedCommand() {
        List<String> inserted = new ArrayList<>();
        Style style = Style.EMPTY.withInsertion("Alice 玩家").withClickEvent(new ClickEvent.RunCommand("/kill"));
        ComponentClickHandler.handle(style, true, inserted::add,
                event -> fail("Shift insertion must take priority over " + event));
        assertEquals(List.of("Alice 玩家"), inserted);
    }

    @Test
    void shiftWithoutInsertionStillSuppressesTheCommand() {
        assertFalse(ComponentClickHandler.handle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/kill")),
                true, text -> fail("no insertion was provided"), event -> fail("Shift must not execute commands")));
    }

    @Test
    void insertionOnlyStyleWorksWithShiftAndDoesNothingWithoutIt() {
        Style style = Style.EMPTY.withInsertion("insert-only");
        List<String> inserted = new ArrayList<>();
        ComponentClickHandler.handle(style, true, inserted::add, event -> fail("no click event"));
        assertEquals(List.of("insert-only"), inserted);
        assertFalse(ComponentClickHandler.handle(style, false,
                text -> fail("requires Shift"), event -> fail("no click event")));
        assertFalse(ComponentClickHandler.handle(null, false,
                text -> fail("no style"), event -> fail("no style")));
    }
}
