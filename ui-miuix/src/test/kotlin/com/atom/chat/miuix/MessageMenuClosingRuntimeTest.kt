package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import java.awt.event.KeyEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import sun.misc.Unsafe

/** Checks the actual stock Miuix buttons while their owning menu remains in exit. */
class MessageMenuClosingRuntimeTest {
    private var clock = 1_000_000_000L
    private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
        isAccessible = true
        get(null) as Unsafe
    }

    @Test
    fun `closing retains normal row surfaces and glyphs in both themes`() {
        for (dark in listOf(false, true)) {
            val fixture = NativeMiuixRuntimeTest.FixtureHost().apply {
                page = "world_chat"
                this.dark = dark
            }
            NativeMiuixRuntime(animated(fixture)).use { runtime ->
                open(runtime)
                val menu = requireNotNull(runtime.boundsForTag("message-shortcuts-7"))
                val labels = listOf("Copy", "Quote", "React").map {
                    requireNotNull(runtime.boundsForText(it))
                }
                val before = capture(requireNotNull(runtime.render(480, 800, 1f, clock)))
                runtime.key(KeyEvent.VK_ESCAPE, KeyEvent.KEY_LOCATION_STANDARD, true, 0)
                runtime.key(KeyEvent.VK_ESCAPE, KeyEvent.KEY_LOCATION_STANDARD, false, 0)
                // Nanosecond steps force prepareFrame without a visible opacity change.
                var closing: NativeMiuixRuntime.PixelFrame? = null
                repeat(4) { closing = runtime.render(480, 800, 1f, ++clock) }
                assertNotNull(runtime.boundsForTag("message-shortcuts-7"), "Exit animation must stay mounted")
                val after = capture(requireNotNull(closing))
                for (label in labels) {
                    val y = ((label[1] + label[3]) / 2).toInt()
                    val x = (menu[2] - 14).toInt()
                    assertEquals(before[y * 480 + x], after[y * 480 + x],
                        "Closing must not fill every row with disabled gray; dark=$dark")
                    var changed = 0
                    var count = 0
                    for (row in label[1].toInt() until label[3].toInt()) {
                        for (column in label[0].toInt() until label[2].toInt()) {
                            count++
                            if (before[row * 480 + column] != after[row * 480 + column]) changed++
                        }
                    }
                    assertTrue(changed <= count / 100,
                        "Closing must retain normal text colors before fade; changed=$changed/$count dark=$dark")
                }
                settle(runtime)
                assertNull(runtime.boundsForTag("message-shortcuts-7"), "Exit must finish")
            }
        }
    }

    @Test
    fun `action dispatches once while disabled outgoing menu remains mounted`() {
        val fixture = NativeMiuixRuntimeTest.FixtureHost().apply { page = "world_chat" }
        NativeMiuixRuntime(animated(fixture)).use { runtime ->
            open(runtime)
            val copy = requireNotNull(runtime.boundsForText("Copy"))
            click(runtime, copy)
            repeat(4) { runtime.render(480, 800, 1f, ++clock) }
            assertNotNull(runtime.boundsForTag("message-shortcuts-7"))
            click(runtime, copy)
            assertEquals(1, fixture.actions.count { it.command() == Command.COPY_MESSAGE })
            settle(runtime)
            assertNull(runtime.boundsForTag("message-shortcuts-7"))
        }
    }

    private fun animated(fixture: NativeMiuixRuntimeTest.FixtureHost) = object : MiuixHost by fixture {
        override fun snapshot(): MiuixHost.State {
            val s = fixture.snapshot()
            return MiuixHost.State(s.route(), s.conversations(), s.messages(), s.composer(),
                s.settings(), s.profile(), s.extras(), s.appearance(), true, s.navigationStyle())
        }
    }

    private fun open(runtime: NativeMiuixRuntime) {
        settle(runtime)
        val body = requireNotNull(runtime.boundsForText("Hello, Minecraft!"))
        click(runtime, body, 1)
        // Move away so the surface and glyph comparisons exclude hover feedback.
        runtime.pointer(0, 1f, 1f, 0f, 0f, -1, 0)
        settle(runtime)
        assertNotNull(runtime.boundsForTag("message-shortcuts-7"))
    }

    private fun click(runtime: NativeMiuixRuntime, bounds: FloatArray, button: Int = 0) {
        val x = (bounds[0] + bounds[2]) / 2
        val y = (bounds[1] + bounds[3]) / 2
        runtime.pointer(1, x, y, 0f, 0f, button, 0)
        runtime.pointer(2, x, y, 0f, 0f, button, 0)
    }

    private fun settle(runtime: NativeMiuixRuntime) {
        repeat(30) {
            clock += 16_666_667
            runtime.render(480, 800, 1f, clock)
        }
    }

    private fun capture(frame: NativeMiuixRuntime.PixelFrame) = IntArray(480 * 800) { index ->
        unsafe.getInt(frame.address() + (index / 480).toLong() * frame.rowBytes() + (index % 480) * 4)
    }
}
