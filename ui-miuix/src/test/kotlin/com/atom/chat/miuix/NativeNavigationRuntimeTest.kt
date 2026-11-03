package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.*
import com.atom.chat.miuix.ui.NavigationPageHost
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Real frame-clock regressions for the host-owned stack and its native exit layers. */
class NativeNavigationRuntimeTest {
    private val fixture = NativeMiuixRuntimeTest.FixtureHost()
    private val host = NavigationHost(fixture)
    private var nanos = 1_000_000_000L

    @Test
    fun `root content and secondary navigation retain running frames until their destination`() {
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime, 40)
            for ((page, motion) in listOf("settings" to "ROOT_FORWARD",
                "settings_section" to "PUSH", "settings" to "POP", "chat_list" to "ROOT_BACKWARD")) {
                host.navigate(page, motion)
                frames(runtime, if (motion.startsWith("ROOT_")) 4 else 7)
                val runningTag = if (motion.startsWith("ROOT_")) "miuix-root-tabs-running" else "miuix-navigation-running"
                assertTrue(runningTag in runtime.getTags(), "$motion must present intermediate frames; tags=${runtime.getTags()}, finished=${host.finished}")
                assertFalse(host.navigation.sequence() in host.finished, "$motion acknowledged before its exit finished")
                frames(runtime, 40)
                assertTrue("miuix-navigation-settled" in runtime.getTags(), "$motion did not settle")
                assertEquals(host.navigation.sequence(), host.finished.last())
            }
        }
    }

    @Test
    fun `zoom style leaves root shell stationary and scales secondary pages`() {
        host.style = "ZOOM"
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime, 40)
            host.navigate("settings", "ROOT_FORWARD")
            val shellBefore = requireNotNull(runtime.boundsForTag("nav-settings"))
            frames(runtime, 4)
            assertTrue("miuix-root-tabs-running" in runtime.getTags())
            assertFalse("miuix-navigation-running" in runtime.getTags(), "Root tabs share the same screen")
            val shellDuring = requireNotNull(runtime.boundsForTag("nav-settings"))
            shellBefore.zip(shellDuring).forEach { (expected, actual) -> assertEquals(expected, actual, .01f) }
            assertFalse(host.navigation.sequence() in host.finished)
            val entering = requireNotNull(runtime.boundsForTag("settings-section-appearance"))
            frames(runtime, 40)
            val settled = requireNotNull(runtime.boundsForTag("settings-section-appearance"))
            entering.zip(settled).forEach { (expected, actual) -> assertEquals(expected, actual, .01f,
                "Root tab content must keep its geometry even with ZOOM selected") }
            assertEquals(host.navigation.sequence(), host.finished.last())

            for ((page, motion) in listOf("settings_section" to "PUSH", "settings" to "POP")) {
                host.navigate(page, motion)
                frames(runtime, 15)
                assertTrue("miuix-navigation-running" in runtime.getTags(), "$motion ZOOM must animate")
                assertFalse(host.navigation.sequence() in host.finished)
                val incomingBounds = if (motion == "PUSH") runtime.boundsForTag("setting-animations") else null
                frames(runtime, 40)
                if (incomingBounds != null) {
                    val finalBounds = requireNotNull(runtime.boundsForTag("setting-animations"))
                    assertTrue(incomingBounds[0] < finalBounds[0] - .25f,
                        "Secondary ZOOM must still present an enlarged incoming page")
                }
                assertTrue("miuix-navigation-settled" in runtime.getTags())
                assertEquals(host.navigation.sequence(), host.finished.last())
            }
            val restored = requireNotNull(runtime.boundsForTag("settings-section-appearance"))
            settled.zip(restored).forEach { (expected, actual) -> assertEquals(expected, actual, .01f) }
            host.animations = false
            host.navigate("settings_section", "PUSH")
            frames(runtime, 3)
            assertTrue("miuix-navigation-settled" in runtime.getTags())
            assertEquals(host.navigation.sequence(), host.finished.last())
        }
    }

    @Test
    fun `disabled animations settle immediately and interrupted motion reaches the newest page`() {
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime, 40)
            host.navigate("settings", "ROOT_FORWARD")
            frames(runtime, 4)
            host.navigate("settings_section", "PUSH")
            frames(runtime, 4)
            host.navigate("chat_list", "POP")
            frames(runtime, 40)
            assertNotNull(runtime.boundsForTag("conversation-world"))
            assertEquals(host.navigation.sequence(), host.finished.last())

            host.animations = false
            host.navigate("settings", "ROOT_FORWARD")
            frames(runtime, 3)
            assertTrue("miuix-navigation-settled" in runtime.getTags())
            assertEquals(host.navigation.sequence(), host.finished.last())
            host.navigate("settings_section", "PUSH")
            frames(runtime, 3)
            assertTrue("miuix-navigation-settled" in runtime.getTags())
            assertNotNull(runtime.boundsForTag("setting-animations"))
        }
    }

    @Test
    fun `leaving the composer stops its native text session and reopening restores the draft`() {
        fixture.page = "world_chat"
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime, 40)
            assertTrue(runtime.text("你好🌏"))
            frames(runtime, 3)
            host.navigate("settings", "POP")
            frames(runtime, 45)
            assertFalse(runtime.hasTextFocus(), "Settings must release the Compose input session")
            assertFalse(fixture.textFocused, "The OS carrier must follow the final Compose input session")
            host.navigate("world_chat", "PUSH")
            frames(runtime, 45)
            assertTrue(runtime.hasTextFocus())
            assertTrue(fixture.textFocused)
            assertEquals("你好🌏", fixture.input)
        }
    }

    @Test
    fun `departing composer disposal cannot take the new composer input session`() {
        fixture.page = "world_chat"
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime, 40)
            assertTrue(fixture.textFocused)
            host.navigate("private_chat", "PUSH")
            frames(runtime, 45)
            assertTrue(fixture.textFocused, "The exit composer's disposal must not hide the new IME")
            assertTrue(runtime.text("draft"), "The current composer must retain its input connection")
            assertEquals("draft", fixture.input)
        }
    }

    @Test
    fun `exit host preserves its snapshot and rejects stale mutations and IME callbacks`() {
        val frozen = fixture.snapshot()
        var interactive = true
        host.navigation = Navigation(42L, "ROOT_FORWARD")
        host.style = "ZOOM"
        host.poke = 314L
        host.hoverPrefix = "tooltip:"
        val pageHost = NavigationPageHost(host, { frozen }) { interactive }
        assertEquals(host.navigation, pageHost.navigation())
        assertEquals("ZOOM", pageHost.pageNavigationStyle())
        assertEquals(314L, pageHost.pokeTime("player"))
        assertEquals("tooltip:run", pageHost.hoverText("run"))
        pageHost.navigationTransitionFinished(99L)
        pageHost.setText("new draft", 9, 9)
        pageHost.requestTextInput(true, 0f, 0f, 1f, 1f)
        assertEquals("new draft", fixture.input)
        interactive = false
        fixture.page = "settings"
        pageHost.perform(Action(Command.CLOSE))
        pageHost.setText("stale", 5, 5)
        pageHost.requestTextInput(false, 0f, 0f, 0f, 0f)
        pageHost.writeClipboard("stale")
        pageHost.navigationTransitionFinished(100L)
        host.poke = 628L
        assertEquals(628L, pageHost.pokeTime("player"), "Exit snapshots still read live avatar pulse timestamps")
        assertEquals("tooltip:run", pageHost.hoverText("run"))
        assertEquals(listOf(99L), host.finished)
        assertSame(frozen, pageHost.snapshot())
        assertEquals("chat_list", pageHost.snapshot().route().page())
        assertEquals("new draft", fixture.input)
        assertTrue(fixture.textFocused)
        assertTrue(fixture.actions.isEmpty())
        assertEquals("", fixture.clipboard)
    }

    private fun frames(runtime: NativeMiuixRuntime, count: Int) {
        repeat(count) { nanos += 16_666_667L; runtime.render(480, 800, 1f, nanos) }
    }

    private class NavigationHost(private val fixture: NativeMiuixRuntimeTest.FixtureHost) : MiuixHost by fixture {
        var navigation = Navigation(0L, "NONE")
        var animations = true
        var style = "SLIDE"
        var poke = 0L
        var hoverPrefix = ""
        val finished = mutableListOf<Long>()
        fun navigate(page: String, motion: String) {
            fixture.page = page
            navigation = Navigation(navigation.sequence() + 1L, motion)
        }
        override fun navigation() = navigation
        override fun pageNavigationStyle() = style
        override fun pokeTime(playerKey: String) = if (playerKey == "player") poke else 0L
        override fun hoverText(interaction: String) = hoverPrefix + interaction
        override fun navigationTransitionFinished(sequence: Long) { finished += sequence }
        override fun snapshot(): State {
            val state = fixture.snapshot()
            return State(state.route(), state.conversations(), state.messages(), state.composer(), state.settings(),
                state.profile(), state.extras(), state.appearance(), animations, state.navigationStyle())
        }
    }
}
