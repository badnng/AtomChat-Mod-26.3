package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost.Command
import com.atom.chat.miuix.api.MiuixHost.Message
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Sends the same wheel events as the Minecraft bridge to the shipping Compose scene. */
class NativeChatScrollTest {
    private val host = NativeMiuixRuntimeTest.FixtureHost().apply { page = "world_chat" }
    private var clock = 1_000_000_000L

    @Test
    fun `chat title and local clock stay fixed when messages arrive and history scrolls`() {
        val template = host.snapshot().messages().single()
        host.messages = (1L..60L).map { message(template, it) }
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            val bar = requireNotNull(runtime.boundsForTag("miuix-top-app-bar"))
            val title = requireNotNull(runtime.boundsForText("World Chat"))
            val time = requireNotNull(runtime.boundsForTag("miuix-top-clock"))
            assertEquals(72f, bar[3] - bar[1], 1f,
                "Chat must use the official 52 dp pinned bar below its 20 dp time area")
            assertTrue(time[1] >= 0f && time[3] <= 20f, "Local time belongs in the panel's safe area")
            assertTrue(title[1] >= time[3],
                "The time and chat title must occupy separate rows")
            host.messages = host.messages!! + message(template, 61)
            settle(runtime)
            assertTrue(visibleIds(runtime).contains(61L), "New messages still follow at the bottom")
            repeat(8) { wheel(runtime, -3f) }
            host.messages = host.messages!! + message(template, 62)
            settle(runtime)
            assertArrayEquals(bar, requireNotNull(runtime.boundsForTag("miuix-top-app-bar")), 1f)
            assertArrayEquals(title, requireNotNull(runtime.boundsForText("World Chat")), 1f)
            assertArrayEquals(time, requireNotNull(runtime.boundsForTag("miuix-top-clock")), 1f)
        }
    }

    @Test
    fun `wheel can reverse after reaching oldest history and jump latest remains clickable`() {
        val template = host.snapshot().messages().single()
        host.messages = (1L..60L).map { message(template, it) }
        host.earlierMessages = 20
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            assertTrue(visibleIds(runtime).contains(60L), "Chat opens at the latest message")
            repeat(80) { wheel(runtime, -3f) }
            assertTrue(visibleIds(runtime).contains(1L), "Wheel up must reach the oldest loaded message: ${visibleIds(runtime)}")
            val oldest = visibleIds(runtime)
            repeat(12) { wheel(runtime, 3f) }
            assertTrue(visibleIds(runtime).min() > oldest.min(),
                "Reversing the wheel at the upper boundary must scroll down: ${visibleIds(runtime)}")
            val jump = requireNotNull(runtime.boundsForTag("chat-jump-latest")) {
                "Reading history must expose the jump-latest button; visible=${visibleIds(runtime)}"
            }
            click(runtime, jump)
            settle(runtime)
            assertTrue(visibleIds(runtime).contains(60L), "Jump latest must return to the final message")
            assertEquals(Command.JUMP_LATEST, host.actions.last().command())
        }
    }

    @Test
    fun `history prepend preserves visible anchor and incoming messages only follow at bottom`() {
        val template = host.snapshot().messages().single()
        host.messages = (21L..80L).map { message(template, it) }
        host.earlierMessages = 20
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            host.messages = host.messages!! + message(template, 81)
            settle(runtime)
            assertTrue(visibleIds(runtime).contains(81L), "New messages follow while reading at the bottom")
            repeat(12) { wheel(runtime, -3f) }
            val anchor = visibleIds(runtime).first()
            val anchorTop = requireNotNull(runtime.boundsForTag("message-$anchor"))[1]
            host.messages = host.messages!! + message(template, 82)
            settle(runtime)
            assertEquals(anchorTop, requireNotNull(runtime.boundsForTag("message-$anchor"))[1], 1f,
                "Incoming messages must not move the history being read")
            host.messages = (1L..20L).map { message(template, it) } + host.messages!!
            host.earlierMessages = 0
            settle(runtime)
            assertEquals(anchorTop, requireNotNull(runtime.boundsForTag("message-$anchor"))[1], 1f,
                "Loading earlier messages must keep the same message at the same screen position")
        }
    }

    @Test
    fun `scene recreation retains history anchor draft and route and keeps scrolling interactive`() {
        val template = host.snapshot().messages().single()
        host.messages = (1L..80L).map { message(template, it) }
        host.navigation = "FLOATING"
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            click(runtime, requireNotNull(runtime.boundsForTag("chat-composer")))
            settle(runtime)
            assertTrue(runtime.text("Unsent draft 恢复"), "The real composer must accept the draft")
            settle(runtime)
            repeat(12) { wheel(runtime, -3f) }
            val before = visibleIds(runtime)
            assertTrue(before.first() > 1L && before.last() < 80L,
                "The test must read the middle of history before scene disposal: $before")
            val anchor = before.first()
            val anchorTop = requireNotNull(runtime.boundsForTag("message-$anchor"))[1]
            val draft = host.input
            val selection = host.cursor to host.anchor
            val route = host.page
            val navigation = host.navigation

            runtime.releaseScene()
            settle(runtime)

            assertEquals(before, visibleIds(runtime), "Scene recreation must restore the visible history")
            assertEquals(anchorTop, requireNotNull(runtime.boundsForTag("message-$anchor"))[1], 1f,
                "Scene recreation must restore the same message at the same screen position")
            assertEquals(draft, host.input, "Scene recreation must retain the unsent draft")
            assertEquals(selection, host.cursor to host.anchor, "Draft selection must survive recreation")
            assertEquals(route, host.page, "Scene recreation must retain the open chat route")
            assertEquals(navigation, host.navigation, "Scene recreation must retain the navigation style")
            assertNotNull(runtime.boundsForTag("chat-composer"), "The restored chat must mount its composer")
            assertNotNull(runtime.boundsForTag("chat-jump-latest"), "History must remain detached from automatic following")

            repeat(3) { wheel(runtime, 3f) }
            assertTrue(visibleIds(runtime).first() > anchor,
                "The restored scene must still route downward wheel events to the chat list")
        }
    }

    private fun message(template: Message, id: Long) = Message(id, template.timestamp(), template.sender(),
        template.own(), template.system(), "Message $id", "Message $id", emptyList(), emptyList(),
        "", "", emptyList(), emptyList(), 1, "", false, emptyList(), false, "12:00", false)

    private fun visibleIds(runtime: NativeMiuixRuntime): List<Long> = runtime.getTags()
        .mapNotNull { it.removePrefix("message-").toLongOrNull() }
        .filter { id -> runtime.boundsForTag("message-$id")?.let { it[3] > 70 && it[1] < 640 } == true }

    private fun wheel(runtime: NativeMiuixRuntime, delta: Float) {
        runtime.pointer(3, 240f, 320f, 0f, delta, -1, 0)
        settle(runtime)
    }

    private fun click(runtime: NativeMiuixRuntime, bounds: FloatArray) {
        val x = (bounds[0] + bounds[2]) / 2
        val y = (bounds[1] + bounds[3]) / 2
        runtime.pointer(1, x, y, 0f, 0f, 0, 0)
        runtime.pointer(2, x, y, 0f, 0f, 0, 0)
    }

    private fun settle(runtime: NativeMiuixRuntime) {
        repeat(24) { clock += 16_666_667; runtime.render(480, 800, 1f, clock) }
    }
}
