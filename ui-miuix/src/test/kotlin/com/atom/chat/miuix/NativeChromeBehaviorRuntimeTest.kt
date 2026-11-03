package com.atom.chat.miuix

import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Pointer regressions against the shipping scene, including host-owned profile permissions. */
class NativeChromeBehaviorRuntimeTest {
    private val fixture = NativeMiuixRuntimeTest.FixtureHost()
    private val host = ChromeHost(fixture)
    private var clock = System.nanoTime()

    @Test
    fun `settings wheel collapses app bar and reverses after oldest boundary`() {
        fixture.page = "settings"
        host.manySections = true
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime)
            val expanded = requireNotNull(runtime.boundsForTag("miuix-top-app-bar"))
            val expandedHeight = expanded[3] - expanded[1]
            val expandedState = appBarStateDiagnostic(runtime)
            assertTrue(requireNotNull(runtime.boundsForText("AtomChat"))[1] >=
                requireNotNull(runtime.boundsForTag("miuix-top-clock"))[3],
                "Expanded title must stay below the panel clock")
            repeat(30) { wheel(runtime, 3f) }
            val collapsed = requireNotNull(runtime.boundsForTag("miuix-top-app-bar"))
            val collapsedState = appBarStateDiagnostic(runtime)
            assertTrue(requireNotNull(runtime.boundsForText("AtomChat"))[1] >=
                requireNotNull(runtime.boundsForTag("miuix-top-clock"))[3],
                "Collapsed title must stay below the panel clock")
            assertTrue(collapsed[3] - collapsed[1] < expandedHeight - 10f,
                "Real wheel scrolling must collapse the large app bar")
            val lowerSection = visibleSections(runtime).max()
            assertTrue(lowerSection > 5, "The list must consume wheel events after collapsing the header")
            repeat(70) { wheel(runtime, -3f) }
            // Compose closes a mouse-wheel batch after 50 ms of wall time, then
            // dispatches the fling that lets Miuix finish its header snap.
            val wheelDeadline = System.nanoTime() + 2_000_000_000L
            do {
                Thread.sleep(10)
                frames(runtime, 5)
                val height = runtime.boundsForTag("miuix-top-app-bar")?.let { it[3] - it[1] }
                if (height != null && kotlin.math.abs(height - expandedHeight) <= 1f) break
            } while (System.nanoTime() < wheelDeadline)
            assertTrue(0 in visibleSections(runtime), "Reverse wheel must restore the beginning of settings")
            val restored = requireNotNull(runtime.boundsForTag("miuix-top-app-bar"))
            assertEquals(expandedHeight, restored[3] - restored[1], 1f,
                "Header must restore after reaching the beginning; " +
                    "expanded=$expandedState collapsed=$collapsedState restored=${appBarStateDiagnostic(runtime)} " +
                    "heights=$expandedHeight->${collapsed[3] - collapsed[1]}->${restored[3] - restored[1]} " +
                    "firstRow=${runtime.boundsForTag("settings-section-probe-0")?.contentToString()} " +
                    "visible=${visibleSections(runtime)} frameNanos=$clock wallNanos=${System.nanoTime()}")
            val beforeSections = visibleSections(runtime).filter {
                runtime.boundsForTag("settings-section-probe-$it") != null
            }
            val anchor = beforeSections.min()
            val anchorTag = "settings-section-probe-$anchor"
            val beforeAnchorY = requireNotNull(runtime.boundsForTag(anchorTag))[1] - restored[3]
            repeat(8) { wheel(runtime, 3f) }
            val afterBar = requireNotNull(runtime.boundsForTag("miuix-top-app-bar"))
            val afterSections = visibleSections(runtime).filter {
                runtime.boundsForTag("settings-section-probe-$it") != null
            }
            val afterAnchorY = runtime.boundsForTag(anchorTag)?.let { it[1] - afterBar[3] }
            // Font metrics affect how many rows fit after the header consumes part of the
            // wheel motion. Check actual list movement, excluding the header's own collapse.
            val advanced = afterSections.minOrNull()?.let { it > anchor } == true ||
                afterAnchorY?.let { it < beforeAnchorY - 1f } == true
            assertTrue(advanced,
                "Downward scrolling must move the list after the upper overscroll boundary; " +
                    "before=$beforeSections after=$afterSections anchor=$anchor " +
                    "relativeY=$beforeAnchorY->$afterAnchorY " +
                    "barHeight=${restored[3] - restored[1]}->${afterBar[3] - afterBar[1]}")
        }
    }

    @Test
    fun `world filter follows all three host states and external changes`() {
        fixture.page = "world_chat"
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime)
            assertTrue("chat-filter-all" in runtime.getTags())
            for (mode in listOf("SYSTEM", "PLAYERS", "ALL")) {
                click(runtime, "chat-filter")
                frames(runtime)
                assertEquals(Command.FILTER, fixture.actions.last().command())
                assertEquals(mode, host.filter)
                assertTrue("chat-filter-${mode.lowercase()}" in runtime.getTags(),
                    "Native icon must follow the host filter state $mode")
            }
            host.filter = "PLAYERS"
            frames(runtime)
            assertTrue("chat-filter-players" in runtime.getTags(),
                "Filter changes from outside the native icon must also update its selected glyph")
        }
    }

    @Test
    fun `remote profile dispatches player actions and never exposes self editors`() {
        fixture.page = "profile_detail"
        host.self = false
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime)
            for (tag in listOf("profile-avatar-edit", "profile-banner", "profile-banner-clear", "profile-signature")) {
                assertFalse(tag in runtime.getTags(), "Remote profile exposes self editor $tag")
            }
            for ((tag, command) in listOf("profile-whisper" to Command.PRIVATE_CHAT,
                "profile-block" to Command.BLOCK, "profile-mention" to Command.MENTION,
                "profile-teleport" to Command.TELEPORT, "profile-poke" to Command.POKE)) {
                reveal(runtime, tag)
                click(runtime, tag)
                frames(runtime)
                assertEquals(command, fixture.actions.last().command())
                assertEquals("remote", fixture.actions.last().target())
            }
        }
    }

    @Test
    fun `offline read only remote profile omits teleport mention and all media editors`() {
        fixture.page = "profile_detail"
        host.self = false
        host.online = false
        host.readOnly = true
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime)
            val seen = mutableSetOf<String>()
            repeat(20) { seen += runtime.getTags(); wheel(runtime, 2f) }
            for (tag in listOf("profile-teleport", "profile-mention", "profile-avatar-edit",
                "profile-banner", "profile-banner-clear", "profile-signature")) {
                assertFalse(tag in seen, "Unavailable remote action rendered: $tag")
            }
            for (tag in listOf("profile-whisper", "profile-block", "profile-poke")) {
                assertTrue(tag in seen, "Available remote action missing: $tag")
            }
        }
    }

    @Test
    fun `self profile retains signature banner uuid and confirmed custom avatar clearing`() {
        fixture.page = "profile"
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime)
            assertNotNull(runtime.boundsForTag("profile-signature"))
            reveal(runtime, "profile-banner")
            click(runtime, "profile-banner")
            assertEquals(Command.PICK_BANNER, fixture.actions.last().command())
            reveal(runtime, "profile-banner-clear")
            click(runtime, "profile-banner-clear")
            assertEquals(Command.CLEAR_BANNER, fixture.actions.last().command())
            reveal(runtime, "profile-uuid-copy")
            click(runtime, "profile-uuid-copy")
            assertEquals(Command.COPY_TEXT, fixture.actions.last().command())
            assertEquals("test-uuid", fixture.actions.last().value())
            repeat(30) { wheel(runtime, -3f) }
            click(runtime, "profile-avatar-edit")
            frames(runtime)
            click(runtime, "profile-avatar-clear")
            frames(runtime)
            assertFalse(fixture.actions.any { it.command() == Command.CLEAR_AVATAR },
                "First clear click must request confirmation")
            click(runtime, "profile-avatar-clear")
            assertEquals(Command.CLEAR_AVATAR, fixture.actions.last().command())
        }
    }

    private fun visibleSections(runtime: NativeMiuixRuntime): List<Int> = runtime.getTags()
        .filter { it.startsWith("settings-section-probe-") }
        .map { it.substringAfterLast('-').toInt() }

    private fun reveal(runtime: NativeMiuixRuntime, tag: String) {
        repeat(35) {
            val b = runtime.boundsForTag(tag)
            if (b != null && b[1] >= 80f && b[3] <= HEIGHT - 85f) return
            wheel(runtime, 1.5f)
        }
        assertNotNull(runtime.boundsForTag(tag), "Missing $tag; ${runtime.getTags()}")
    }

    private fun frames(runtime: NativeMiuixRuntime, count: Int = 28) {
        repeat(count) {
            // Pointer events use System.nanoTime too. Keep test frames on the same
            // monotonic origin and catch up after expensive software-rendered frames.
            clock = maxOf(clock + 16_666_667L, System.nanoTime())
            runtime.render(WIDTH, HEIGHT, 1f, clock)
        }
    }

    /** Inspect the existing saver rather than adding a production-only diagnostic API. */
    private fun appBarStateDiagnostic(runtime: NativeMiuixRuntime): String = runCatching {
        val field = NativeMiuixRuntime::class.java.getDeclaredField("sceneRegistry")
        field.isAccessible = true
        val registry = field.get(runtime) as? SaveableStateRegistry ?: return@runCatching "no scene registry"
        val bars = mutableListOf<String>()
        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> value.values.forEach(::visit)
                is List<*> -> {
                    if (value.isNotEmpty() && value.size % 6 == 0) {
                        value.chunked(6).forEach { row ->
                            if (row.take(3).all { it is String } && row.drop(3).all { it is Number }) {
                                bars += "page=${row[0]}/${row[1]}/${row[2]} " +
                                    "limit=${row[3]} offset=${row[4]} contentOffset=${row[5]}"
                            }
                        }
                    }
                    value.forEach(::visit)
                }
            }
        }
        val saved = registry.performSave()
        visit(saved)
        if (bars.isEmpty()) "no bar rows; registry=$saved" else bars.joinToString("; ")
    }.getOrElse { "state diagnostic failed: $it" }

    private fun wheel(runtime: NativeMiuixRuntime, amount: Float) {
        runtime.pointer(3, WIDTH / 2f, HEIGHT / 2f, 0f, amount, -1, 0)
        frames(runtime, 5)
    }

    private fun click(runtime: NativeMiuixRuntime, tag: String) {
        val b = requireNotNull(runtime.boundsForTag(tag)) { "Missing $tag; ${runtime.getTags()}" }
        val x = (b[0] + b[2]) / 2f
        val y = (b[1] + b[3]) / 2f
        runtime.pointer(1, x, y, 0f, 0f, 0, 0)
        runtime.pointer(2, x, y, 0f, 0f, 0, 0)
    }

    private class ChromeHost(private val fixture: NativeMiuixRuntimeTest.FixtureHost) : MiuixHost by fixture {
        var self = true
        var online = true
        var readOnly = false
        var manySections = false
        var filter = "ALL"

        override fun perform(action: Action) {
            fixture.perform(action)
            if (action.command() == Command.FILTER) filter = when (filter) {
                "ALL" -> "SYSTEM"
                "SYSTEM" -> "PLAYERS"
                else -> "ALL"
            }
        }

        override fun snapshot(): State {
            val s = fixture.snapshot()
            val p = s.profile()
            val original = p.player()
            val player = Player(if (self) "self" else "remote", original.uuid(), original.name(),
                original.displayName(), online, false, "", "")
            val route = s.route()
            val settings = s.settings()
            val extras = s.extras()
            return State(Route(route.page(), route.targetKey(), route.title(), route.section(),
                route.canGoBack(), readOnly, ""), s.conversations(), s.messages(), s.composer(),
                if (manySections) Settings(settings.section(), (0..39).map { Section("probe-$it", "Probe section $it") },
                    settings.rows(), settings.presets(), settings.blockedPlayers()) else settings,
                Profile(player, self, "Exploring the Overworld", "", true, true, false, false,
                    p.statistics(), p.facts()), Extras(extras.emoji(), extras.stickers(), extras.phrases(),
                    extras.earlierMessages(), extras.feedback(), extras.feedbackSuccess(), extras.cropEditor(),
                    extras.suggestions(), extras.notices(), extras.jump(), filter),
                s.appearance(), s.animations(), s.navigationStyle())
        }
    }

    companion object {
        private const val WIDTH = 480
        private const val HEIGHT = 800
    }
}
