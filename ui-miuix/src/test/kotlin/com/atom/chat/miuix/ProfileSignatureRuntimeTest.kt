package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost.Command
import java.awt.event.KeyEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The inline signature uses the production Compose IME session and preserves the chat draft. */
class ProfileSignatureRuntimeTest {
    private val host = NativeMiuixRuntimeTest.FixtureHost().apply {
        page = "profile"
        input = "parked chat draft"
        cursor = input.length
        anchor = cursor
    }
    private var clock = 1_000_000_000L

    @Test
    fun `inline Unicode composition saves with Enter without changing composer`() {
        NativeMiuixRuntime(host).use { runtime ->
            openEditor(runtime)
            selectAll(runtime)
            runtime.composingText("你好")
            frames(runtime)
            runtime.finishComposingText()
            assertTrue(runtime.text("🌏"))
            frames(runtime)
            press(runtime, KeyEvent.VK_ENTER)
            frames(runtime)
            val saved = host.actions.single { it.command() == Command.SAVE_SIGNATURE }
            assertEquals("你好🌏", saved.value())
            assertNull(runtime.boundsForTag("profile-signature-editor"))
            assertEquals("parked chat draft", host.input)
        }
    }

    @Test
    fun `Escape cancels inline signature without saving or changing composer`() {
        NativeMiuixRuntime(host).use { runtime ->
            openEditor(runtime)
            selectAll(runtime)
            assertTrue(runtime.text("discard this"))
            frames(runtime)
            press(runtime, KeyEvent.VK_ESCAPE)
            frames(runtime)
            assertTrue(host.actions.none { it.command() == Command.SAVE_SIGNATURE })
            assertNull(runtime.boundsForTag("profile-signature-editor"))
            assertEquals("parked chat draft", host.input)
        }
    }

    @Test
    fun `signature text retains the original 256 character limit`() {
        NativeMiuixRuntime(host).use { runtime ->
            openEditor(runtime)
            selectAll(runtime)
            assertTrue(runtime.text("界".repeat(300)))
            frames(runtime)
            press(runtime, KeyEvent.VK_ENTER)
            frames(runtime)
            assertEquals("界".repeat(256), host.actions.single { it.command() == Command.SAVE_SIGNATURE }.value())
            assertEquals("parked chat draft", host.input)
        }
    }

    @Test
    fun `outside profile click commits signature before dispatching its action`() {
        NativeMiuixRuntime(host).use { runtime ->
            openEditor(runtime)
            selectAll(runtime)
            assertTrue(runtime.text("save on blur"))
            frames(runtime)
            click(runtime, "profile-banner")
            frames(runtime)
            val saveIndex = host.actions.indexOfFirst { it.command() == Command.SAVE_SIGNATURE }
            val bannerIndex = host.actions.indexOfFirst { it.command() == Command.PICK_BANNER }
            assertTrue(saveIndex >= 0, "Outside press must commit the inline signature")
            assertTrue(bannerIndex > saveIndex, "Commit must precede the clicked action")
            assertEquals("save on blur", host.actions[saveIndex].value())
            assertNull(runtime.boundsForTag("profile-signature-editor"))
            assertEquals("parked chat draft", host.input)
        }
    }

    @Test
    fun `changing root tab commits signature before navigation and preserves composer`() {
        NativeMiuixRuntime(host).use { runtime ->
            openEditor(runtime)
            selectAll(runtime)
            assertTrue(runtime.text("save before leaving profile"))
            frames(runtime)
            click(runtime, "nav-settings")
            frames(runtime)
            val saveIndex = host.actions.indexOfFirst { it.command() == Command.SAVE_SIGNATURE }
            val rootIndex = host.actions.indexOfFirst { it.command() == Command.ROOT && it.target() == "settings" }
            assertTrue(saveIndex >= 0, "Leaving the profile must commit its focused signature")
            assertTrue(rootIndex > saveIndex, "Save must run while the old page host is still interactive")
            assertEquals("save before leaving profile", host.actions[saveIndex].value())
            assertEquals("settings", host.page)
            assertEquals("parked chat draft", host.input)
        }
    }

    @Test
    fun `returning to chats commits focused signature before root callback`() {
        NativeMiuixRuntime(host).use { runtime ->
            openEditor(runtime)
            selectAll(runtime)
            assertTrue(runtime.text("save before returning to chats"))
            frames(runtime)
            click(runtime, "nav-chats")
            frames(runtime)
            val saveIndex = host.actions.indexOfFirst { it.command() == Command.SAVE_SIGNATURE }
            val rootIndex = host.actions.indexOfFirst { it.command() == Command.ROOT && it.target() == "chat_list" }
            assertTrue(saveIndex >= 0, "Returning to chats must commit the focused signature")
            assertTrue(rootIndex > saveIndex, "Save must precede root callback")
            assertEquals("save before returning to chats", host.actions[saveIndex].value())
            assertEquals("chat_list", host.page)
            assertEquals("parked chat draft", host.input)
        }
    }

    private fun openEditor(runtime: NativeMiuixRuntime) {
        frames(runtime)
        click(runtime, "profile-signature")
        frames(runtime)
        assertNotNull(runtime.boundsForTag("profile-signature-editor"))
        assertTrue(runtime.hasTextFocus(), "Inline field must own the native input session")
    }

    private fun selectAll(runtime: NativeMiuixRuntime) {
        runtime.key(KeyEvent.VK_A, KeyEvent.KEY_LOCATION_STANDARD, true, 2)
        runtime.key(KeyEvent.VK_A, KeyEvent.KEY_LOCATION_STANDARD, false, 2)
        frames(runtime)
    }

    private fun press(runtime: NativeMiuixRuntime, key: Int) {
        runtime.key(key, KeyEvent.KEY_LOCATION_STANDARD, true, 0)
        runtime.key(key, KeyEvent.KEY_LOCATION_STANDARD, false, 0)
    }

    private fun click(runtime: NativeMiuixRuntime, tag: String) {
        val b = requireNotNull(runtime.boundsForTag(tag)) { "Missing $tag: ${runtime.getTags()}" }
        val x = (b[0] + b[2]) / 2f
        val y = (b[1] + b[3]) / 2f
        runtime.pointer(1, x, y, 0f, 0f, 0, 0)
        runtime.pointer(2, x, y, 0f, 0f, 0, 0)
    }

    private fun frames(runtime: NativeMiuixRuntime) {
        repeat(24) { clock += 16_666_667L; runtime.render(480, 800, 1f, clock) }
    }
}
