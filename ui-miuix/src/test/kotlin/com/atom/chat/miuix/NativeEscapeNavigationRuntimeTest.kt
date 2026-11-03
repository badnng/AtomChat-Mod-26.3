package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.*
import com.atom.chat.miuix.api.NativeNavigationInput
import java.awt.event.KeyEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Uses the shipping scene and host input coordinator across unpublished and animated routes. */
class NativeEscapeNavigationRuntimeTest {
    @Test
    fun `explicit close survives a queued back and an acknowledged pop`() {
        val input = NativeNavigationInput()
        input.defer(NativeNavigationInput.Request.BACK)
        input.defer(NativeNavigationInput.Request.CLOSE)
        assertNull(input.takeReady(false))
        assertEquals(NativeNavigationInput.Request.CLOSE, input.takeReady(true))
        input.backStarted(7L)
        assertTrue(input.pressEscape())
        assertFalse(input.pressEscape())
        input.releaseEscape() // Window focus loss resets the physical hold too.
        assertTrue(input.pressEscape())
        assertTrue(input.backInFlight(), "Resetting a lost key-up must retain navigation arbitration")
        input.defer(NativeNavigationInput.Request.CLOSE)
        input.defer(NativeNavigationInput.Request.BACK)
        input.transitionFinished(7L) // The old push's acknowledgement cannot release the pop.
        assertNull(input.takeReady(true))
        input.transitionFinished(8L)
        assertEquals(NativeNavigationInput.Request.CLOSE, input.takeReady(true))
        assertNull(input.takeReady(true))
    }

    @Test
    fun `quick chat escape coalesces through push and pop and a fresh root escape still closes`() {
        for (style in listOf("SLIDE", "ZOOM")) {
            val host = EscapeHost(style)
            var nanos = 1_000_000_000L
            NativeMiuixRuntime(host).use { runtime ->
                fun frames(count: Int) {
                    repeat(count) {
                        // Raster draw cost must not advance the animation under test.
                        nanos += 16_666_667L
                        runtime.render(480, 800, 1f, nanos)
                    }
                }
                frames(40)
                val card = requireNotNull(runtime.boundsForTag("conversation-world"))
                val x = (card[0] + card[2]) / 2
                val y = (card[1] + card[3]) / 2
                runtime.pointer(1, x, y, 0f, 0f, 0, 0)
                runtime.pointer(2, x, y, 0f, 0f, 0, 0)
                assertEquals("world_chat", host.fixture.page)
                // No render yet: the domain has changed, but Compose still displays the root.
                repeat(4) { host.escape(runtime); host.release(runtime) }
                frames(7)
                assertTrue("miuix-navigation-running" in runtime.getTags(), "$style push must remain coherent")
                assertEquals(0, host.closes)
                frames(100)
                assertEquals(1, host.backs, "$style quick presses must represent one back intent")
                assertEquals(0, host.closes, "$style back must not leak into closing the outer panel")
                assertNotNull(runtime.boundsForTag("conversation-world"))
                assertTrue("miuix-navigation-settled" in runtime.getTags())
                assertFalse(runtime.hasTextFocus(), "A departed chat must release its input connection")

                // Exercise the other chat route after its incoming animation has already begun.
                host.openChat("private_chat")
                frames(6)
                host.escape(runtime)
                repeat(4) { host.escape(runtime) } // OS key-repeat while Escape is held.
                frames(100)
                assertEquals(2, host.backs)
                assertEquals(0, host.closes)
                host.escape(runtime) // Still held after settling must not become a fresh Close.
                assertEquals(0, host.closes)
                host.release(runtime)
                assertNotNull(runtime.boundsForTag("conversation-world"), "The root must remain operable")
                host.escape(runtime)
                assertEquals(1, host.closes, "A fresh Escape on the settled root must still close")
            }
        }
    }

    private class EscapeHost(private val style: String,
                             val fixture: NativeMiuixRuntimeTest.FixtureHost = NativeMiuixRuntimeTest.FixtureHost()) : MiuixHost by fixture {
        private val input = NativeNavigationInput()
        private var navigation = Navigation(0L, "NONE")
        private var settled = -1L
        private var unpublishedMotion: String? = null
        var backs = 0
        var closes = 0

        fun openChat(page: String) {
            fixture.page = page
            unpublishedMotion = "PUSH"
        }

        private fun busy() = unpublishedMotion != null || settled < navigation.sequence()

        fun escape(runtime: NativeMiuixRuntime) {
            if (!input.pressEscape()) return
            if (runtime.key(KeyEvent.VK_ESCAPE, KeyEvent.KEY_LOCATION_STANDARD, true, 0)) return
            if (input.backInFlight()) return
            if (fixture.page in listOf("world_chat", "private_chat")) {
                if (busy()) input.defer(NativeNavigationInput.Request.BACK) else back()
            } else if (!busy()) closes++
        }

        fun release(runtime: NativeMiuixRuntime) {
            input.releaseEscape()
            runtime.key(KeyEvent.VK_ESCAPE, KeyEvent.KEY_LOCATION_STANDARD, false, 0)
        }

        private fun back() {
            input.backStarted(navigation.sequence())
            backs++
            fixture.page = "chat_list"
            unpublishedMotion = "POP"
        }

        private fun publish() {
            unpublishedMotion?.let {
                navigation = Navigation(navigation.sequence() + 1L, it)
                unpublishedMotion = null
            }
        }

        override fun perform(action: Action) {
            fixture.perform(action)
            if (action.command() == Command.WORLD_CHAT) openChat("world_chat")
        }

        override fun navigation() = navigation
        override fun pageNavigationStyle() = style
        override fun navigationTransitionFinished(sequence: Long) {
            if (sequence == navigation.sequence()) {
                settled = sequence
                input.transitionFinished(sequence)
            }
        }

        override fun setText(text: String, cursor: Int, anchor: Int) = fixture.setText(text, cursor, anchor)
        override fun requestTextInput(focused: Boolean, x: Float, y: Float, width: Float, height: Float) =
            fixture.requestTextInput(focused, x, y, width, height)

        override fun snapshot(): State {
            publish()
            if (input.takeReady(!busy()) == NativeNavigationInput.Request.BACK) {
                back()
                publish()
            }
            val state = fixture.snapshot()
            val route = state.route()
            return State(Route(route.page(), route.targetKey(), route.title(), route.section(),
                fixture.page in listOf("world_chat", "private_chat"), route.readOnly(), route.readOnlyHint()),
                state.conversations(), state.messages(), state.composer(), state.settings(), state.profile(),
                state.extras(), state.appearance(), true, state.navigationStyle())
        }
    }
}
