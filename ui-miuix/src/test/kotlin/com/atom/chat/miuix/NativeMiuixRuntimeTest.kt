package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.*
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import sun.misc.Unsafe

/** Exercises the shipping Compose scene and real Miuix widgets, without a Minecraft window. */
class NativeMiuixRuntimeTest {
    private val host = FixtureHost()
    private var clock = 1_000_000_000L

    @Test
    fun `all primary pages render real semantics and save light and dark previews`() {
        NativeMiuixRuntime(host).use { runtime ->
            runtime.setWorldFrame(WIDTH, HEIGHT, checkerWorld(), 1, 0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
            for (dark in listOf(false, true)) {
                host.dark = dark
                for (page in listOf("chat_list", "profile", "settings", "world_chat", "settings_section")) {
                    host.page = page
                    val frame = settle(runtime)
                    assertTrue(runtime.getTags().contains("miuix-top-app-bar"), "$page has no official app bar")
                    val expected = when (page) {
                        "chat_list" -> "conversation-world"
                        "settings" -> "settings-section-appearance"
                        "settings_section" -> "setting-animations"
                        "world_chat" -> "chat-composer"
                        else -> "nav-profile"
                    }
                    assertNotNull(runtime.boundsForTag(expected), "$page missing $expected; ${runtime.getTags()}")
                    val image = capture(frame)
                    assertTrue(pixels(image).toSet().size > 32, "$page produced a blank image")
                    save(image, "${if (dark) "dark" else "light"}-$page.png")
                }
            }
        }
    }

    @Test
    fun `stationary pointer polling retains pixels while real pointer actions still navigate`() {
        host.blur = false
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            runtime.pointer(4, 1f, 1f, 0f, 0f, -1, 0)
            val stable = settle(runtime).generation()
            repeat(24) {
                runtime.pointer(0, 1f, 1f, 0f, 0f, -1, 0)
                assertEquals(stable, runtime.render(WIDTH, HEIGHT, 1f, nextTime())!!.generation(),
                    "Stationary pointer polling must retain the already rendered frame")
            }

            val settings = requireNotNull(runtime.boundsForTag("nav-settings"))
            // Movement, exit/enter and modifier changes still reach the real scene.
            runtime.pointer(5, 1f, 1f, 0f, 0f, -1, 0)
            runtime.pointer(4, (settings[0] + settings[2]) / 2, (settings[1] + settings[3]) / 2,
                0f, 0f, -1, 1)
            runtime.pointer(0, (settings[0] + settings[2]) / 2, (settings[1] + settings[3]) / 2,
                0f, 0f, -1, 0)
            click(runtime, "nav-settings")
            assertEquals("settings", host.page)
            settle(runtime)
            click(runtime, "settings-section-appearance")
            settle(runtime)
            click(runtime, "setting-animations")
            assertEquals(Action(Command.SET_SWITCH, "animations", "true"), host.actions.last())
        }
    }

    @Test
    fun `pointer navigation and official switch dispatch host actions`() {
        NativeMiuixRuntime(host).use { runtime ->
            for (style in listOf("ICON_AND_TEXT", "ICON_ONLY", "SELECTED_LABEL", "FLOATING")) {
                host.navigation = style
                host.page = "chat_list"
                settle(runtime)
                click(runtime, "nav-settings")
                assertEquals("settings", host.page, "$style navigation")
                settle(runtime)
                click(runtime, "settings-section-appearance")
                assertEquals("settings_section", host.page)
                settle(runtime)
                click(runtime, "setting-animations")
                assertEquals(Action(Command.SET_SWITCH, "animations", "true"), host.actions.last())
            }
        }
    }

    @Test
    fun `root navigation has a bounded full width surface and floating ends follow panel corners`() {
        NativeMiuixRuntime(host).use { runtime ->
            for (dark in listOf(false, true)) {
                host.dark = dark
                for (style in listOf("ICON_AND_TEXT", "ICON_ONLY", "SELECTED_LABEL", "FLOATING")) {
                    host.navigation = style
                    host.page = "chat_list"
                    val frame = settle(runtime)
                    val bar = requireNotNull(runtime.boundsForTag(if (style == "FLOATING")
                        "floating-navigation-bar" else "miuix-root-navigation-bar"))
                    if (style == "FLOATING") {
                        // Fixture panel radius is 20 dp; the upstream border is 0.75 dp.
                        assertEquals(20f, bar[0], 2f, "Capsule must start at the outer corner arc")
                        assertEquals(WIDTH - 20f, bar[2], 2f, "Capsule must end at the outer corner arc")
                    } else {
                        assertEquals(0f, bar[0], 1f)
                        assertEquals(WIDTH.toFloat(), bar[2], 1f)
                    }
                    val items = listOf("nav-chats", "nav-profile", "nav-settings").map {
                        requireNotNull(runtime.boundsForTag(it))
                    }
                    val centers = items.map { (it[0] + it[2]) / 2 }
                    assertTrue(centers[1] - centers[0] > WIDTH / 4f,
                        "$style items must use the available bar width")
                    assertEquals(centers[1] - centers[0], centers[2] - centers[1], 1f)
                    assertTrue(items.all { it[1] >= bar[1] && it[3] <= bar[3] })
                    save(capture(frame), "${if (dark) "dark" else "light"}-navigation-${style.lowercase()}.png")
                }
            }
        }
    }

    @Test
    fun `first pointer event before rendering is bounded in a small viewport and survives resize`() {
        host.page = "world_chat"
        NativeMiuixRuntime(host).use { runtime ->
            // Style switches can deliver focus and pointer events before the first frame.
            // These events synchronously measure the pinned app bar in Compose.
            runtime.resizeViewport(267, 430, .444f)
            runtime.setFocused(false)
            runtime.pointer(0, 133f, 215f, 0f, 0f, -1, 0)
            runtime.pointer(4, 133f, 215f, 0f, 0f, -1, 0)
            repeat(24) { runtime.render(267, 430, .444f, nextTime()) }
            for (tag in listOf("miuix-top-app-bar", "chat-composer", "chat-send")) {
                val bounds = requireNotNull(runtime.boundsForTag(tag)) { "Small viewport lost $tag" }
                assertTrue(bounds[0] >= 0 && bounds[1] >= 0 && bounds[2] <= 267 && bounds[3] <= 430,
                    "$tag must stay within the initial physical viewport: ${bounds.toList()}")
            }

            host.page = "settings"
            runtime.resizeViewport(1080, 1920, 1.8f)
            runtime.setFocused(true)
            runtime.pointer(0, 540f, 960f, 0f, 0f, -1, 0)
            repeat(24) { runtime.render(1080, 1920, 1.8f, nextTime()) }
            click(runtime, "settings-section-appearance")
            assertEquals("settings_section", host.page)
            repeat(24) { runtime.render(1080, 1920, 1.8f, nextTime()) }
            click(runtime, "setting-animations")
            assertEquals(Action(Command.SET_SWITCH, "animations", "true"), host.actions.last())
        }
    }

    @Test
    fun `small viewport emoji and phrase popups render lazy contents and dismiss correctly`() {
        host.page = "world_chat"
        host.phrases = listOf(Phrase(0, "Regression phrase", false))
        NativeMiuixRuntime(host).use { runtime ->
            runtime.resizeViewport(267, 430, .444f)
            fun renderPopup() { repeat(24) { runtime.render(267, 430, .444f, nextTime()) } }
            renderPopup()
            click(runtime, "chat-emoji")
            renderPopup() // Miuix queries the popup item's intrinsic height around the lazy list.
            assertNotNull(runtime.boundsForText("😀"), "The emoji grid must be visible in the popup")
            clickText(runtime, "😀")
            assertEquals(Action(Command.APPEND, "", "😀"), host.actions.last())
            click(runtime, "chat-emoji")
            renderPopup()
            assertNull(runtime.boundsForText("😀"), "Dismissed emoji contents must leave the semantics tree")

            click(runtime, "chat-phrases")
            renderPopup()
            assertNotNull(runtime.boundsForText("Regression phrase"), "The phrase list must be visible")
            click(runtime, "chat-phrases")
            renderPopup()
            assertNull(runtime.boundsForText("Regression phrase"), "Dismissed phrase contents must leave the semantics tree")
            assertNotNull(runtime.boundsForTag("chat-composer"), "Popup dismissal must retain the composer")
        }
    }

    @Test
    fun `canceling phrase editor returns typing directly to the composer`() {
        host.page = "world_chat"
        host.input = "seed"
        host.cursor = host.input.length
        host.anchor = host.cursor
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            click(runtime, "chat-phrases")
            settle(runtime)
            clickText(runtime, "Add")
            settle(runtime)
            assertNotNull(runtime.boundsForText("Cancel"), "The phrase editor must be visible")
            assertTrue(runtime.text("draft"), "The editor must own the platform text input session")
            settle(runtime)
            assertEquals("seed", host.input, "Editing a phrase must not modify the chat composer")

            clickText(runtime, "Cancel")
            settle(runtime)
            assertNull(runtime.boundsForText("Cancel"), "Cancel must dismiss the editor, not only its old popup")
            assertTrue(runtime.text("恢复"), "Cancel must restore typing without clicking the composer")
            settle(runtime)
            assertEquals("seed恢复", host.input)
            assertTrue(host.actions.none { it.command() == Command.SAVE_PHRASE }, "Cancel must not save the draft")
        }
    }

    @Test
    fun `real text input session commits CJK Unicode composition and clipboard paste`() {
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            assertFalse(runtime.text("unfocused"))
            host.page = "world_chat"
            settle(runtime)
            click(runtime, "chat-composer")
            settle(runtime)
            assertTrue(runtime.hasTextFocus(), "Pointer focus must open the platform text input session")
            assertTrue(runtime.text("你好🌏"))
            settle(runtime)
            assertEquals("你好🌏", host.input)
            runtime.composingText("世界")
            settle(runtime)
            runtime.finishComposingText()
            settle(runtime)
            assertEquals("你好🌏世界", host.input)
            host.clipboard = " 剪贴板🚀"
            runtime.key(KeyEvent.VK_V, KeyEvent.KEY_LOCATION_STANDARD, true, 2)
            runtime.key(KeyEvent.VK_V, KeyEvent.KEY_LOCATION_STANDARD, false, 2)
            settle(runtime)
            assertEquals("你好🌏世界 剪贴板🚀", host.input)
            click(runtime, "chat-send")
            assertEquals(Command.SEND, host.actions.last().command())
        }
        assertFalse(host.textFocused)
    }

    @Test
    fun `IME candidate keys preserve preedit until SDL commits each codepoint`() {
        host.page = "world_chat"
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            click(runtime, "chat-composer")
            settle(runtime)
            runtime.composingText("nihao", 3)
            settle(runtime)
            assertTrue(runtime.isComposingText())
            val selection = host.cursor to host.anchor
            val actions = host.actions.toList()
            for (key in listOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP,
                KeyEvent.VK_DOWN, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_SPACE,
                KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE)) {
                assertTrue(runtime.key(key, KeyEvent.KEY_LOCATION_STANDARD, true, 0))
                assertTrue(runtime.key(key, KeyEvent.KEY_LOCATION_STANDARD, false, 0))
            }
            settle(runtime)
            assertEquals("nihao", host.input, "Candidate keys belong to the native IME")
            assertEquals(selection, host.cursor to host.anchor)
            assertEquals(actions, host.actions, "IME Enter must not send; Escape must not close")

            // Match KeyboardHandler.textInput: one null preedit, then one callback per codepoint.
            runtime.cancelComposingText()
            "你好🌏".codePoints().forEach { assertTrue(runtime.text(String(Character.toChars(it)))) }
            settle(runtime)
            assertFalse(runtime.isComposingText())
            assertEquals("你好🌏", host.input)
            runtime.key(KeyEvent.VK_ENTER, KeyEvent.KEY_LOCATION_STANDARD, true, 0)
            assertEquals(Command.SEND, host.actions.last().command())
        }
    }

    @Test
    fun `IME preedit cancellation and commit in one event batch never duplicate text`() {
        host.page = "world_chat"
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            click(runtime, "chat-composer")
            settle(runtime)
            runtime.composingText("你好", 1)
            runtime.cancelComposingText()
            assertTrue(runtime.text("你好")) // Minecraft delivers these callbacks before rendering.
            settle(runtime)
            assertEquals("你好", host.input)

            selectAll(runtime)
            val selection = host.cursor to host.anchor
            runtime.cancelComposingText() // A null preedit without composition must preserve selection.
            settle(runtime)
            assertEquals("你好", host.input)
            assertEquals(selection, host.cursor to host.anchor)
            assertTrue(runtime.text("替换"))
            settle(runtime)
            assertEquals("替换", host.input)
        }
    }

    @Test
    fun `native preedit caret clamps to UTF16 composition bounds`() {
        host.page = "world_chat"
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            click(runtime, "chat-composer")
            settle(runtime)
            assertTrue(runtime.text("A🌏B"))
            settle(runtime)
            runtime.composingText("你好", -10)
            settle(runtime)
            assertEquals("A🌏B你好", host.input)
            assertEquals(4, host.cursor, "The negative caret must clamp after four UTF-16 code units")
            runtime.composingText("你好", Int.MAX_VALUE)
            settle(runtime)
            assertEquals("A🌏B你好", host.input)
            assertEquals(6, host.cursor)
            runtime.cancelComposingText()
            assertTrue(runtime.text("世界"))
            settle(runtime)
            assertEquals("A🌏B世界", host.input)
            assertEquals(6, host.cursor)
        }
    }

    @Test
    fun `renderer scene rebuild cancels native preedit before restoring the host draft`() {
        host.page = "world_chat"
        host.input = "seed"
        host.cursor = 4
        host.anchor = 4
        NativeMiuixRuntime(host).use { runtime ->
            settle(runtime)
            click(runtime, "chat-composer")
            settle(runtime)
            runtime.composingText("你好", 1)
            settle(runtime)
            assertEquals("seed你好", host.input)
            runtime.releaseScene()
            assertEquals("seed", host.input, "An uncommitted OS composition must not become a restored draft")
            assertFalse(host.textFocused)
            settle(runtime)
            assertEquals("world_chat", host.page)
            assertNotNull(runtime.boundsForTag("chat-composer"))
            click(runtime, "chat-composer")
            settle(runtime)
            assertTrue(runtime.text("世界"))
            settle(runtime)
            assertEquals("seed世界", host.input, "The next committed event must appear exactly once")
        }
    }

    @Test
    fun `mounted stable avatar and wallpaper polling does not redraw idle pixel frames`() {
        host.page = "profile"
        host.stableImages = true
        NativeMiuixRuntime(host).use { runtime ->
            val generation = settle(runtime).generation()
            repeat(3) {
                val requests = host.imageRequests
                // Image polling uses real coroutine delay, independent of the supplied render clock.
                Thread.sleep(140)
                val frame = settle(runtime)
                assertTrue(host.imageRequests >= requests + 2, "Mounted avatar and wallpaper must both poll")
                assertEquals(generation, frame.generation(), "Unchanged image bytes must not redraw the raster")
            }
        }
    }

    @Test
    fun `world generations crop resize idle and repeated disposal keep native ownership valid`() {
        val runtime = NativeMiuixRuntime(host)
        try {
            assertNull(runtime.render(0, HEIGHT, 1f, clock))
            val source = checkerWorld()
            runtime.setWorldFrame(WIDTH, HEIGHT, source, 1, 0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
            val first = pixels(capture(settle(runtime))).copyOf()
            val firstGeneration = settle(runtime).generation()
            // Reusing a generation must not re-import a replacement CPU buffer.
            runtime.setWorldFrame(WIDTH, HEIGHT, ByteArray(source.size), 1, 0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
            assertEquals(firstGeneration, settle(runtime).generation(), "A retained source generation must not repaint Compose")
            assertArrayEquals(first, pixels(capture(settle(runtime))))
            runtime.setWorldFrame(WIDTH, HEIGHT, checkerWorld(invert = true), 2, 0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())
            val updated = pixels(capture(settle(runtime)))
            assertTrue(first.indices.count { first[it] != updated[it] } > WIDTH * HEIGHT / 5,
                "World capture must affect actual mixed blur output")
            assertTrue((0 until WIDTH * 90).count { first[it] != updated[it] } > WIDTH * 10,
                "The progressive app bar must sample the world-backed page, including above content padding")
            assertTrue(((HEIGHT - 80) * WIDTH until HEIGHT * WIDTH).count { first[it] != updated[it] } > WIDTH * 8,
                "The progressive navigation bar must sample the world-backed page below content padding")
            runtime.setWorldFrame(WIDTH, HEIGHT, source, 3, 40f, 70f, 320f, 600f)
            assertTrue(settle(runtime).address() != 0L)
            val resized = runtime.render(600, 900, 1.25f, nextTime())!!
            assertEquals(600, resized.width())
            assertEquals(900, resized.height())
            assertTrue(resized.rowBytes() >= 600 * 4)
            host.blur = false
            settle(runtime)
            val stable = settle(runtime)
            assertEquals(stable.generation(), runtime.render(WIDTH, HEIGHT, 1f, nextTime())!!.generation(),
                "An idle scene must reuse the native pixel frame")
            assertEquals(0, host.imageRequests, "World capture must never request encoded PNG from the host")
        } finally {
            runtime.close()
            runtime.close()
        }
        assertThrows(IllegalStateException::class.java) { runtime.render(WIDTH, HEIGHT, 1f, nextTime()) }
    }

    @Test
    fun `prepared frames alternate with raster fallback while retaining navigation and native ownership`() {
        // Preparation is also allowed before a renderer ever allocates its first target.
        val undrawn = NativeMiuixRuntime(host)
        undrawn.prepareFrame(WIDTH, HEIGHT, 1f, nextTime())
        undrawn.releaseGpu()
        undrawn.close()
        undrawn.close()

        val runtime = NativeMiuixRuntime(host)
        try {
            assertFalse(runtime.prepareFrame(0, HEIGHT, 1f, nextTime()))
            val stages = listOf(
                Triple("chat_list", WIDTH, HEIGHT),
                Triple("settings", 600, 900),
                Triple("settings_section", WIDTH, HEIGHT),
            )
            for ((page, width, height) in stages) {
                assertEquals(page, host.page)
                repeat(24) {
                    val time = nextTime()
                    runtime.prepareFrame(width, height, 1f, time)
                    // Hosts may prepare repeatedly while deciding which backend owns this frame.
                    runtime.prepareFrame(width, height, 1f, time)
                    val frame = requireNotNull(runtime.render(width, height, 1f, time))
                    assertEquals(width, frame.width())
                    assertEquals(height, frame.height())
                    assertTrue(frame.address() != 0L)
                }
                val beforeRelease = requireNotNull(runtime.render(width, height, 1f, nextTime()))
                runtime.releaseGpu()
                runtime.releaseGpu() // Raster fallback must survive redundant GPU detach requests.
                val retained = requireNotNull(runtime.render(width, height, 1f, nextTime()))
                assertEquals(beforeRelease.generation(), retained.generation())
                assertTrue(pixels(capture(retained)).toSet().size > 32)
                when (page) {
                    "chat_list" -> click(runtime, "nav-settings")
                    "settings" -> click(runtime, "settings-section-appearance")
                    else -> {
                        click(runtime, "setting-animations")
                        assertEquals(Action(Command.SET_SWITCH, "animations", "true"), host.actions.last())
                    }
                }
            }
        } finally {
            runtime.releaseGpu()
            runtime.close()
            runtime.close()
        }
    }

    private fun nextTime(): Long { clock += 16_666_667; return clock }
    private fun settle(runtime: NativeMiuixRuntime): NativeMiuixRuntime.PixelFrame {
        var frame: NativeMiuixRuntime.PixelFrame? = null
        repeat(24) { frame = runtime.render(WIDTH, HEIGHT, 1f, nextTime()) }
        return requireNotNull(frame)
    }
    private fun click(runtime: NativeMiuixRuntime, tag: String) {
        val b = requireNotNull(runtime.boundsForTag(tag)) { "$tag missing; ${runtime.getTags()}" }
        val x = (b[0] + b[2]) / 2
        val y = (b[1] + b[3]) / 2
        runtime.pointer(1, x, y, 0f, 0f, 0, 0)
        runtime.pointer(2, x, y, 0f, 0f, 0, 0)
    }
    private fun selectAll(runtime: NativeMiuixRuntime) {
        runtime.key(KeyEvent.VK_A, KeyEvent.KEY_LOCATION_STANDARD, true, 2)
        runtime.key(KeyEvent.VK_A, KeyEvent.KEY_LOCATION_STANDARD, false, 2)
        settle(runtime)
        assertEquals(0, minOf(host.cursor, host.anchor))
        assertEquals(host.input.length, maxOf(host.cursor, host.anchor))
    }
    private fun clickText(runtime: NativeMiuixRuntime, text: String) {
        val bounds = requireNotNull(runtime.boundsForText(text)) { "No visible text: $text" }
        val x = (bounds[0] + bounds[2]) / 2
        val y = (bounds[1] + bounds[3]) / 2
        runtime.pointer(1, x, y, 0f, 0f, 0, 0)
        runtime.pointer(2, x, y, 0f, 0f, 0, 0)
    }

    private fun checkerWorld(invert: Boolean = false): ByteArray = ByteArray(WIDTH * HEIGHT * 4).apply {
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val bright = ((x / 6 + y / 6) and 1) == 0
            val i = (y * WIDTH + x) * 4
            this[i] = (if (invert) { if (bright) 45 else 60 } else { if (bright) 245 else 15 }).toByte()
            this[i + 1] = (if (invert) { if (bright) 180 else 220 } else { if (bright) 35 else 210 }).toByte()
            this[i + 2] = (if (invert) { if (bright) 50 else 80 } else { if (bright) 65 else 235 }).toByte()
            this[i + 3] = 255.toByte()
        }
    }
    private fun capture(frame: NativeMiuixRuntime.PixelFrame): BufferedImage {
        assertTrue(frame.address() != 0L)
        val image = BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB_PRE)
        val target = pixels(image)
        for (y in 0 until frame.height()) for (x in 0 until frame.width()) {
            target[y * frame.width() + x] = unsafe.getInt(frame.address() + y.toLong() * frame.rowBytes() + x * 4)
        }
        return image
    }
    private fun pixels(image: BufferedImage) = (image.raster.dataBuffer as DataBufferInt).data
    private fun save(image: BufferedImage, name: String) {
        val directory = Path.of(System.getProperty("atomchat.previewDir", "build/native-preview"))
        Files.createDirectories(directory)
        assertTrue(ImageIO.write(image, "png", directory.resolve(name).toFile()))
    }

    internal class FixtureHost : MiuixHost {
        var messages: List<Message>? = null
        var earlierMessages = 0
        var page = "chat_list"
        var navigation = "ICON_AND_TEXT"
        var dark = false
        var blur = true
        var opacity = 1f
        var input = ""
        var cursor = 0
        var anchor = 0
        var textFocused = false
        var clipboard = ""
        var imageRequests = 0
        var stableImages = false
        var phrases: List<Phrase> = emptyList()
        val actions = mutableListOf<Action>()
        private val player get() = Player("self", "test-uuid", "Alex", "Alex", true, false,
            if (stableImages) "avatar" else "", "")
        private val imageBytes by lazy {
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 16) for (x in 0 until 16) image.setRGB(x, y, 0xff3482ff.toInt())
            ByteArrayOutputStream().use { output -> ImageIO.write(image, "png", output); output.toByteArray() }
        }
        override fun snapshot() = State(
            Route(page, "", if (page == "world_chat") "World Chat" else "AtomChat", "appearance", page == "world_chat" || page == "settings_section", false, ""),
            listOf(Conversation("world", "world", null, "World Chat", "Hello, Minecraft!", 1, 3, true, false)),
            messages ?: listOf(Message(7, 1, player, false, false, "Hello, Minecraft!", "Hello, Minecraft!",
                emptyList(), listOf(Run("Hello, Minecraft!", null, false, false, false, false, false, "")),
                "", "", emptyList(), emptyList(), 1, "", true, listOf(Reaction("🌏", 2, false, listOf("Alex", "Steve"), 1)), false, "12:00", false)),
            Composer(input, cursor, anchor, textFocused, true, null, "", "", false, "TEXT"),
            Settings("appearance", listOf(Section("appearance", "Appearance")),
                listOf(Setting("animations", "switch", "Animations", "Animate transitions", "appearance", true, false,
                    0f, 0f, 1f, 1f, "", 0, emptyList(), "", false, emptyList(), 0)), emptyList(), emptyList()),
            Profile(player, true, "Exploring the Overworld", "", false, false, false, false,
                listOf(Fact("Messages", "42", "")), listOf(Fact("Name", "Alex", "Alex"))),
            Extras(listOf("😀", "🌏"), emptyList(), phrases, earlierMessages, "", true, null, emptyList(), emptyList(), null),
            Appearance(0xff3482ff.toInt(), (if (dark) 0xff181818 else 0xfff5f5f5).toInt(), 0xff111111.toInt(),
                0xff666666.toInt(), 0xff3482ff.toInt(), -1, -1, 0xff111111.toInt(), opacity, 20f, blur,
                if (stableImages) "wallpaper" else "", "SQUARE"), false, navigation)
        override fun perform(action: Action) {
            actions.add(action)
            when (action.command()) {
                Command.ROOT -> page = action.target()
                Command.SETTINGS_SECTION -> page = "settings_section"
                else -> Unit
            }
        }
        override fun setText(text: String, cursor: Int, anchor: Int) { input = text; this.cursor = cursor; this.anchor = anchor }
        override fun requestTextInput(focused: Boolean, x: Float, y: Float, width: Float, height: Float) { textFocused = focused }
        override fun image(key: String): ByteArray? {
            imageRequests++
            return if (stableImages) imageBytes.copyOf() else null
        }
        override fun text(key: String, vararg arguments: Any?): String = when (key) {
            "atomchat.tab.chat" -> "Chats"
            "atomchat.tab.settings" -> "Settings"
            "atomchat.page.profile.title" -> "Profile"
            "atomchat.input.placeholder" -> "Message"
            "gui.close" -> "Close"
            "gui.back" -> "Back"
            else -> key.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
        override fun readClipboard() = clipboard
        override fun writeClipboard(text: String) { clipboard = text }
    }
    companion object {
        private const val WIDTH = 480
        private const val HEIGHT = 800
        private val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
