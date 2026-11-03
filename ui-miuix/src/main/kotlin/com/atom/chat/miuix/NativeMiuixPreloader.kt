package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.*
import com.atom.chat.miuix.ui.preloadMiuixTypography

/** Startup-only composition. No game services, window input, pixels or GL objects are involved. */
internal object NativeMiuixPreloader {
    private var complete = false

    @Synchronized
    fun preload() {
        if (complete) return
        preloadMiuixTypography()
        // Finish before client setup returns, so this never races a visible scene.
        // The temporary scene is created, advanced and closed on this same thread;
        // only ordinary library/font caches survive, never a scene or its state.
        val host = PreloadHost()
        NativeMiuixRuntime(host).use { runtime ->
            var nanos = 1_000_000_000L
            for (page in listOf("world_chat", "chat_list")) {
                host.page = page
                repeat(2) {
                    runtime.prepareFrame(480, 800, 1f, nanos)
                    nanos += 16_666_667L
                }
            }
        }
        // A failed optional attempt can still be retried, and never marks a
        // partially initialized scene as reusable by the real interface.
        complete = true
    }

    private class PreloadHost : MiuixHost {
        var page = "world_chat"
        private val player = Player("preload", "", "AtomChat", "AtomChat", false, false, "", "")
        private val conversations = listOf(
            Conversation("preload", "world", null, "AtomChat", "你好, Minecraft!", 0, 0, false, false),
        )
        private val messages = listOf(
            Message(0, 0, player, false, false, "你好, Minecraft!", "你好, Minecraft!", emptyList(),
                listOf(Run("你好, Minecraft!", null, false, false, false, false, false, "")),
                "", "", emptyList(), emptyList(), 1, "", false, emptyList(), false, "12:00", false),
        )
        private val composer = Composer("", 0, 0, false, true, null, "", "", false, "TEXT")
        private val settings = Settings("", emptyList(), emptyList(), emptyList(), emptyList())
        private val extras = Extras(emptyList(), emptyList(), emptyList(), 0, "", false, null,
            emptyList(), emptyList(), null)
        private val appearance = Appearance(0xff3482ff.toInt(), 0xfff5f5f5.toInt(),
            0xff111111.toInt(), 0xff666666.toInt(), 0xff3482ff.toInt(), -1, -1,
            0xff111111.toInt(), 1f, 20f, false, "", "SQUARE")

        override fun snapshot() = State(
            Route(page, "", "AtomChat", "", page == "world_chat", false, ""),
            conversations, messages, composer, settings, null, extras, appearance, false, "ICON_AND_TEXT",
        )

        override fun worldBackdropRequired(state: State) = false
        override fun perform(action: Action) = Unit
        override fun setText(text: String, cursor: Int, anchor: Int) = Unit
        override fun requestTextInput(focused: Boolean, x: Float, y: Float, width: Float, height: Float) = Unit
        override fun image(key: String): ByteArray? = null
        override fun text(translationKey: String, vararg arguments: Any?) = "AtomChat"
        override fun readClipboard(): String? = null
        override fun writeClipboard(text: String) = Unit
    }
}
