package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

/** Real Miuix pointer gestures whose values change the host's own viewport. */
class NativeSettingSliderRuntimeTest {
    private var nanos = 1_000_000_000L

    @Test
    fun `geometry sliders keep dragging at a stable size and resize once on release`() {
        for (id in listOf("width", "scale", "contentscale")) {
            val host = SliderHost(id)
            NativeMiuixRuntime(host).use { runtime ->
                frames(runtime, host)
                val before = requireNotNull(runtime.boundsForTag("miuix-top-app-bar"))
                val row = requireNotNull(runtime.boundsForTag("setting-$id"))
                val x = row[0] + 40f
                val y = row[3] - 32f
                runtime.pointer(1, x, y, 0f, 0f, 0, 0)
                for (step in 1..12) {
                    runtime.pointer(0, x + step * 40f, y, 0f, 0f, -1, 0)
                    frames(runtime, host, 2)
                    assertTrue(host.actions.isEmpty(), "$id must leave viewport geometry stable while pressed")
                    assertArrayEquals(before, runtime.boundsForTag("miuix-top-app-bar"), .01f)
                }
                assertNotNull(runtime.boundsForText(host.format(host.maximum)),
                    "$id must preview the selected value while retaining its host geometry")
                runtime.pointer(2, x + 480f, y, 0f, 0f, 0, 0)
                frames(runtime, host)
                assertEquals(1, host.actions.size, "$id must apply and persist once per gesture")
                assertTrue(host.actions.single().commit())
                assertEquals(host.maximum, host.value, .001f)
                val resized = requireNotNull(runtime.boundsForTag("miuix-top-app-bar"))
                assertTrue(resized[0] >= 0 && resized[1] >= 0 && resized[2] <= host.width && resized[3] <= host.height,
                    "$id must render bounded controls after the retained frame is replaced")

                // A second gesture must still work after the viewport/density replacement.
                val second = requireNotNull(runtime.boundsForTag("setting-$id"))
                val left = second[0] + 40f * host.density
                val secondY = second[3] - 32f * host.density
                runtime.pointer(1, left + 200f * host.density, secondY, 0f, 0f, 0, 0)
                runtime.pointer(0, left, secondY, 0f, 0f, -1, 0)
                frames(runtime, host, 3)
                runtime.pointer(2, left, secondY, 0f, 0f, 0, 0)
                frames(runtime, host)
                assertEquals(2, host.actions.size, "$id must accept another drag after resizing")
                assertTrue(host.value < host.maximum, "$id must be adjustable in the reverse direction")
            }
        }
    }

    @Test
    fun `focus loss settles pending geometry and permits another drag without physical release`() {
        val host = SliderHost("scale")
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime, host)
            val row = requireNotNull(runtime.boundsForTag("setting-scale"))
            val y = row[3] - 32f
            runtime.pointer(1, row[0] + 50f, y, 0f, 0f, 0, 0)
            runtime.pointer(0, row[2] - 40f, y, 0f, 0f, -1, 0)
            frames(runtime, host, 3)
            assertTrue(host.actions.isEmpty())
            runtime.setFocused(false)
            frames(runtime, host)
            assertEquals(1, host.actions.size, "Losing focus must finish the drag even when the OS omits button release")
            assertTrue(host.actions.single().commit())
            runtime.setFocused(true)
            frames(runtime, host)
            val again = requireNotNull(runtime.boundsForTag("setting-scale"))
            val secondY = again[3] - 32f * host.density
            runtime.pointer(1, again[2] - 50f * host.density, secondY, 0f, 0f, 0, 0)
            runtime.pointer(0, again[0] + 40f * host.density, secondY, 0f, 0f, -1, 0)
            frames(runtime, host, 3)
            runtime.pointer(2, again[0] + 40f * host.density, secondY, 0f, 0f, 0, 0)
            frames(runtime, host)
            assertEquals(2, host.actions.size)
        }
    }

    @Test
    fun `opacity keeps live preview and commits on release`() {
        val host = SliderHost("opacity")
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime, host)
            val row = requireNotNull(runtime.boundsForTag("setting-opacity"))
            val y = row[3] - 32f
            runtime.pointer(1, row[0] + 40f, y, 0f, 0f, 0, 0)
            runtime.pointer(0, row[2] - 40f, y, 0f, 0f, -1, 0)
            frames(runtime, host, 3)
            assertTrue(host.actions.any { !it.commit() }, "Non-geometric appearance changes must preview immediately")
            runtime.pointer(2, row[2] - 40f, y, 0f, 0f, 0, 0)
            frames(runtime, host)
            assertTrue(host.actions.last().commit())
        }
    }

    private fun frames(runtime: NativeMiuixRuntime, host: SliderHost, count: Int = 24) {
        repeat(count) {
            nanos += 16_666_667
            val frame = requireNotNull(runtime.render(host.width, host.height, host.density, nanos))
            assertEquals(host.width, frame.width())
            assertEquals(host.height, frame.height())
        }
    }

    private class SliderHost(val id: String,
        private val fixture: NativeMiuixRuntimeTest.FixtureHost = NativeMiuixRuntimeTest.FixtureHost()
    ) : MiuixHost by fixture {
        val minimum = if (id == "width") 400f else .75f
        val maximum = if (id == "width") 600f else 1.5f
        var value = if (id == "width") 480f else 1f
        val actions = mutableListOf<Action>()
        val density get() = if (id in listOf("scale", "contentscale")) value else 1f
        val width get() = (if (id == "width") value else 480f * density).roundToInt()
        val height get() = (800f * density).roundToInt()

        init { fixture.page = "settings_section"; fixture.blur = false }
        fun format(value: Float) = when (id) {
            "scale" -> "x" + String.format(java.util.Locale.ROOT, "%.2f", value)
            "contentscale", "opacity" -> "${(value * 100).roundToInt()}%"
            else -> value.roundToInt().toString()
        }
        override fun snapshot(): State {
            val state = fixture.snapshot()
            val setting = Setting(id, "slider", id, "", "", true, false,
                value, minimum, maximum, if (id == "width") 10f else .05f,
                format(value), 0, emptyList(), "", false, emptyList(), 0)
            return State(state.route(), state.conversations(), state.messages(), state.composer(),
                Settings("appearance", emptyList(), listOf(setting), emptyList(), emptyList()),
                state.profile(), state.extras(), state.appearance(), false, state.navigationStyle())
        }
        override fun perform(action: Action) {
            if (action.command() == Command.SET_SLIDER) {
                actions += action
                value = action.value().toFloat()
            } else fixture.perform(action)
        }
    }
}
