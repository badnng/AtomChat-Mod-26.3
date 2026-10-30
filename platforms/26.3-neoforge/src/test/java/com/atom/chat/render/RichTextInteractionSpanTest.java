package com.atom.chat.render;

import com.atom.chat.font.FontManager;
import com.atom.chat.text.RichText;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Surface;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RichTextInteractionSpanTest {
    @Test
    void insertionAndHoverOnlyRunsProduceHitRectanglesButPlainTextDoesNot() {
        Style insertion = Style.EMPTY.withInsertion("Alice");
        Style hover = Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(Component.literal("profile")));
        RichText text = RichText.of(Component.literal("").append(Component.literal("Alice").withStyle(insertion))
                .append(Component.literal("plain"))
                .append(Component.literal("hover").withStyle(hover)));
        Font font = FontManager.font(16);
        List<ClickableSpan> spans = new ArrayList<>();
        try (Surface surface = Surface.makeRasterN32Premul(400, 80)) {
            RichTextRenderer.drawLines(surface.getCanvas(), font, RichTextRenderer.wrapFor(text, font, 380),
                    10, 30, 24, 0xFFFFFFFF, spans, true);
        }
        assertEquals(2, spans.size(), "both insertion and hover styles must survive real rendering");
        assertEquals("Alice", spans.getFirst().style().getInsertion());
        assertNull(spans.getFirst().style().getClickEvent());
        assertEquals(hover.getHoverEvent(), spans.getLast().style().getHoverEvent());
        assertTrue(spans.stream().allMatch(span -> span.w() > 0 && span.h() == 24));
        assertTrue(spans.getLast().x() > spans.getFirst().x() + spans.getFirst().w(),
                "the unstyled middle run must advance the second hit rectangle");
    }

    @Test
    void disabledInteractionDoesNotPublishInsertionOrHoverRectangles() {
        Font font = FontManager.font(16);
        RichText text = RichText.of(Component.literal("Alice").withStyle(Style.EMPTY.withInsertion("Alice")));
        List<ClickableSpan> spans = new ArrayList<>();
        try (Surface surface = Surface.makeRasterN32Premul(200, 80)) {
            RichTextRenderer.drawLines(surface.getCanvas(), font, RichTextRenderer.wrapFor(text, font, 180),
                    10, 30, 24, 0xFFFFFFFF, spans, false);
        }
        assertTrue(spans.isEmpty());
    }
}
