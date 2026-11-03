package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import sun.misc.Unsafe

class ComposerEmojiRuntimeTest {
    private var clock = System.nanoTime()

    @Test
    fun `one emoji tool opens three tabs and square cells preserve insertion`() {
        for (animations in listOf(false, true)) {
            val fixture = NativeMiuixRuntimeTest.FixtureHost().apply { page = "world_chat" }
            val host = emojiHost(fixture, animations)
            NativeMiuixRuntime(host).use { runtime ->
                frames(runtime)
                assertNull(runtime.boundsForTag("chat-stickers"), "Stickers belong inside the emoji popup")
                clickTag(runtime, "chat-emoji")
                frames(runtime)
                val cell = requireNotNull(runtime.boundsForTag("composer-emoji-cell-😀"))
                assertEquals(cell[2] - cell[0], cell[3] - cell[1], 1f, "Emoji cells must be square")
                clickTag(runtime, "composer-emoji-cell-😀")
                assertEquals(Action(Command.APPEND, "", "😀"), fixture.actions.last())

                clickText(runtime, "Kaomoji")
                frames(runtime)
                clickTag(runtime, "composer-kaomoji-cell-(^_^)")
                assertEquals(Action(Command.APPEND, "", "(^_^)"), fixture.actions.last())
                clickText(runtime, "Stickers")
                frames(runtime)
                assertNotNull(runtime.boundsForTag("sticker-add"))
                clickTag(runtime, "sticker-add")
                assertEquals(Command.ADD_STICKER, fixture.actions.last().command())
                clickText(runtime, "Emoji")
                frames(runtime)
                assertNotNull(runtime.boundsForTag("composer-emoji-cell-😀"),
                    "Changing categories must retain a usable grid with animations=$animations")
            }
        }
    }

    @Test
    fun `tab content moves through intermediate positions only when animation is enabled`() {
        for (animations in listOf(false, true)) {
            val fixture = NativeMiuixRuntimeTest.FixtureHost().apply { page = "world_chat" }
            NativeMiuixRuntime(emojiHost(fixture, animations)).use { runtime ->
                runtime.resizeViewport(360, 600, 1f)
                frames(runtime, width = 360, height = 600)
                clickTag(runtime, "chat-emoji")
                frames(runtime, width = 360, height = 600)
                val emoji = requireNotNull(runtime.boundsForTag("composer-emoji-cell-😀"))
                clickText(runtime, "Kaomoji")
                val enteringPositions = mutableListOf<Float>()
                repeat(20) {
                    frames(runtime, count = 1, width = 360, height = 600)
                    runtime.boundsForTag("composer-kaomoji-cell-(^_^)")?.let { enteringPositions += it[0] }
                }
                frames(runtime, width = 360, height = 600)
                val settled = requireNotNull(runtime.boundsForTag("composer-kaomoji-cell-(^_^)"))
                assertEquals(emoji[0], settled[0], 1f, "Both category grids must settle at the same left inset")
                assertTrue(enteringPositions.isNotEmpty(), "The destination must appear within the sampled frames")
                if (animations) {
                    assertTrue(enteringPositions.any { it > settled[0] + 2f && it < 359f },
                        "Animated content must pass between the right edge and its final position: $enteringPositions")
                } else {
                    enteringPositions.forEach { x ->
                        assertEquals(settled[0], x, 1f,
                            "Disabled motion must present the destination directly at its final position")
                    }
                }
                val after = fixture.actions.size
                clickTag(runtime, "composer-kaomoji-cell-(^_^)")
                assertEquals(after + 1, fixture.actions.size, "The settled page must accept input")
            }
        }
    }

    @Test
    fun `emoji entry closes every category and phrase entry reopens after outside dismissal`() {
        for (animations in listOf(false, true)) {
            val fixture = NativeMiuixRuntimeTest.FixtureHost().apply {
                page = "world_chat"
                phrases = listOf(Phrase(0, "Toggle phrase", false))
            }
            NativeMiuixRuntime(emojiHost(fixture, animations)).use { runtime ->
                frames(runtime)
                for (category in listOf("Kaomoji", "Stickers")) {
                    clickTag(runtime, "chat-emoji")
                    frames(runtime)
                    clickText(runtime, category)
                    frames(runtime)
                    clickTag(runtime, "chat-emoji")
                    frames(runtime)
                    assertNull(runtime.boundsForTag("composer-emoji-tabs"),
                        "The shared entry must dismiss $category with animations=$animations")
                }
                clickTag(runtime, "chat-emoji")
                frames(runtime)
                clickText(runtime, "Stickers")
                frames(runtime)
                clickTag(runtime, "chat-phrases")
                frames(runtime)
                // Upstream OverlayListPopup consumes the outside click to dismiss its host.
                // The underlying composer tool receives the following click after dismissal.
                assertNull(runtime.boundsForTag("composer-emoji-tabs"))
                assertNull(runtime.boundsForText("Toggle phrase"))
                clickTag(runtime, "chat-phrases")
                frames(runtime)
                assertNotNull(runtime.boundsForText("Toggle phrase"))
                assertNull(runtime.boundsForTag("composer-emoji-tabs"))
                clickTag(runtime, "chat-emoji")
                frames(runtime)
                assertNull(runtime.boundsForText("Toggle phrase"))
                clickTag(runtime, "chat-emoji")
                frames(runtime)
                assertNotNull(runtime.boundsForTag("composer-emoji-page"))
                clickTag(runtime, "chat-phrases")
                frames(runtime)
                assertNull(runtime.boundsForTag("composer-emoji-tabs"))
                clickTag(runtime, "chat-phrases")
                frames(runtime)
                assertNotNull(runtime.boundsForText("Toggle phrase"))
                clickTag(runtime, "chat-phrases")
                frames(runtime)
                assertNull(runtime.boundsForText("Toggle phrase"))
            }
        }
    }

    @Test
    fun `sticker add highlights only while the pointer is over its square`() {
        val fixture = NativeMiuixRuntimeTest.FixtureHost().apply { page = "world_chat" }
        NativeMiuixRuntime(emojiHost(fixture, false)).use { runtime ->
            frames(runtime)
            clickTag(runtime, "chat-emoji")
            frames(runtime)
            clickText(runtime, "Stickers")
            frames(runtime)
            val bounds = requireNotNull(runtime.boundsForTag("sticker-add"))
            assertEquals(bounds[2] - bounds[0], bounds[3] - bounds[1], 1f)
            val sampleX = bounds[0].toInt() + 12
            val sampleY = bounds[1].toInt() + 12
            val idlePixel = pixel(frames(runtime), sampleX, sampleY)
            runtime.pointer(0, (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2, 0f, 0f, -1, 0)
            val hoverPixel = pixel(frames(runtime), sampleX, sampleY)
            assertNotNull(runtime.boundsForTag("sticker-add-hovered"))
            assertNotEquals(idlePixel, hoverPixel, "Hover must visibly change the cell surface")
            runtime.pointer(0, 2f, 2f, 0f, 0f, -1, 0)
            val restoredPixel = pixel(frames(runtime), sampleX, sampleY)
            assertNotNull(runtime.boundsForTag("sticker-add-idle"))
            assertNull(runtime.boundsForTag("sticker-add-hovered"))
            assertEquals(idlePixel, restoredPixel, "Leaving the cell must remove its hover surface")
        }
    }

    private fun emojiHost(fixture: NativeMiuixRuntimeTest.FixtureHost, animations: Boolean): MiuixHost =
        object : MiuixHost by fixture {
            override fun snapshot(): State {
                val s = fixture.snapshot()
                return State(s.route(), s.conversations(), s.messages(), s.composer(), s.settings(),
                    s.profile(), s.extras(), s.appearance(), animations, s.navigationStyle())
            }
            override fun kaomoji() = listOf("(^_^)")
            override fun text(key: String, vararg arguments: Any?): String = when (key) {
                "atomchat.emoji.tab.emoji" -> "Emoji"
                "atomchat.emoji.tab.kaomoji" -> "Kaomoji"
                "atomchat.emoji.tab.emote" -> "Stickers"
                else -> fixture.text(key, *arguments)
            }
        }

    private fun frames(runtime: NativeMiuixRuntime, count: Int = 80, width: Int = 480,
                       height: Int = 800): NativeMiuixRuntime.PixelFrame {
        var frame: NativeMiuixRuntime.PixelFrame? = null
        repeat(count) { clock += 16_666_667; frame = runtime.render(width, height, 1f, clock) }
        return requireNotNull(frame)
    }

    private fun pixel(frame: NativeMiuixRuntime.PixelFrame, x: Int, y: Int): Int =
        unsafe.getInt(frame.address() + y.toLong() * frame.rowBytes() + x * 4)

    private fun clickTag(runtime: NativeMiuixRuntime, tag: String) =
        click(runtime, requireNotNull(runtime.boundsForTag(tag)) { "Missing tag $tag" })

    private fun clickText(runtime: NativeMiuixRuntime, text: String) =
        click(runtime, requireNotNull(runtime.boundsForText(text)) { "Missing tab $text" })

    private fun click(runtime: NativeMiuixRuntime, bounds: FloatArray) {
        val x = (bounds[0] + bounds[2]) / 2
        val y = (bounds[1] + bounds[3]) / 2
        runtime.pointer(1, x, y, 0f, 0f, 0, 0)
        runtime.pointer(2, x, y, 0f, 0f, 0, 0)
    }

    companion object {
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
