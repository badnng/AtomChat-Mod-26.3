package com.atom.chat.miuix

import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.*
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import sun.misc.Unsafe
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Exercise the shipping About route: real metadata, links and existing settings callbacks. */
class AboutPageRuntimeTest {
    private val fixture = NativeMiuixRuntimeTest.FixtureHost()
    private val host = AboutHost(fixture)
    private var clock = 1_000_000_000L

    @Test
    fun `about preserves AtomChat brand authors links and debug actions through scrolling`() {
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime)
            assertNotNull(runtime.boundsForTag("about-official-background"),
                "The shipping about route must mount the official shader background")
            val logo = requireNotNull(runtime.boundsForTag("about-logo"))
            val name = requireNotNull(runtime.boundsForTag("about-name"))
            assertNotNull(runtime.boundsForTag("about-wordmark"), "The original wordmark bitmap must decode and render")
            val version = requireNotNull(runtime.boundsForTag("about-version"))
            // Original AtomChat uses its 55-unit icon at the default 1.25 UI scale.
            assertEquals(68.75f, logo[2] - logo[0], 1f)
            assertEquals(240f, (logo[0] + logo[2]) / 2f, 1f)
            assertTrue(name[2] - name[0] > logo[2] - logo[0], "The original wide brand wordmark must replace the text title")
            assertTrue(name[1] >= logo[3])
            assertTrue(version[1] >= name[3])
            assertNotNull(runtime.boundsForText("9.8.7-test"), "The badge must use the original loader-supplied version without a prefix")
            assertTrue(host.imageKeys.containsAll(listOf("about-logo", "about-wordmark")),
                "The brand must load the actual bundled logo and wordmark")
            click(runtime, "about-version")
            assertEquals(Action(Command.OPEN_LINK, RELEASES), fixture.actions.last())
            reveal(runtime, "about-authors")
            val authorLeft = requireNotNull(runtime.boundsForTag("about-author-e33epus"))
            val authorRight = requireNotNull(runtime.boundsForTag("about-author-nowordz"))
            assertTrue(authorLeft[2] <= authorRight[0], "The original authors must occupy separate columns")
            assertEquals(authorLeft[1], authorRight[1], 1f)
            assertEquals(authorLeft[3], authorRight[3], 1f)
            assertNotNull(runtime.boundsForText("E33EPUS"))
            assertNotNull(runtime.boundsForText("NoWordz"))
            assertNotNull(runtime.boundsForTag("about-author-image-e33epus"))
            assertNotNull(runtime.boundsForTag("about-author-image-nowordz"))
            assertTrue(host.imageKeys.containsAll(listOf("about-author-e33epus", "about-author-nowordz")),
                "Both author portraits must load their real bundled assets")
            for ((id, uri) in listOf("source" to SOURCE, "license" to LICENSE, "thirdparty" to THIRD_PARTY)) {
                reveal(runtime, "setting-$id")
                click(runtime, "setting-$id")
                assertEquals(Action(Command.OPEN_LINK, uri), fixture.actions.last())
            }
            reveal(runtime, "setting-debug")
            click(runtime, "setting-debug")
            assertEquals(Action(Command.SET_SWITCH, "debug", "true"), fixture.actions.last())
            repeat(24) { wheel(runtime, -3f) }
            assertNotNull(runtime.boundsForText("9.8.7-test"), "Returning to the top must restore the brand hero")
            assertNotNull(runtime.boundsForTag("about-name"), "The real wordmark must scroll back into view")
            click(runtime, "about-version")
            assertEquals(Action(Command.OPEN_LINK, RELEASES), fixture.actions.last(),
                "Returning to the top must restore the visible version's release action")
        }
    }

    @Test
    fun `original about groups collapse and restore their actions independently`() {
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime)
            for ((group, child) in listOf("modinfo" to "source", "thirdparty" to "thirdparty", "advanced" to "debug")) {
                reveal(runtime, "about-group-$group")
                click(runtime, "about-group-$group")
                assertNull(runtime.boundsForTag("setting-$child"), "$group must hide its action rows when collapsed")
                click(runtime, "about-group-$group")
                reveal(runtime, "setting-$child")
                assertNotNull(runtime.boundsForTag("setting-$child"), "$group must restore its original action rows")
            }
        }
    }

    @Test
    fun `opaque about skips world imports and blur until another page needs them`() {
        NativeMiuixRuntime(host).use { runtime ->
            val world = ByteArray(24 * 24 * 4) { if (it % 4 == 3) 255.toByte() else 80.toByte() }
            runtime.setWorldFrame(24, 24, world, 1L, 0f, 0f, 24f, 24f)
            frames(runtime)
            assertNotNull(runtime.boundsForTag("about-official-background"))
            assertNull(runtime.boundsForTag("miuix-world-source"), "Opaque About must not record an unused world layer")
            assertNull(runtime.boundsForTag("miuix-world-page-glass"), "Opaque About must not blur a covered world layer")
            assertEquals(0L, (runtime.statistics()["worldImports"] as Number).toLong())
            assertEquals(0L, (runtime.statistics()["worldCrops"] as Number).toLong())
            host.transitionBackdrop = true
            frames(runtime)
            assertNotNull(runtime.boundsForTag("miuix-world-source"), "An outgoing page may still need the live world during transition")
            val duringTransition = (runtime.statistics()["worldImports"] as Number).toLong()
            assertTrue(duringTransition > 0)
            host.transitionBackdrop = false
            runtime.setWorldFrame(24, 24, world.copyOf(), 2L, 0f, 0f, 24f, 24f)
            frames(runtime)
            assertNull(runtime.boundsForTag("miuix-world-source"), "Settling must unmount the source even when the About snapshot is unchanged")
            assertEquals(duringTransition, (runtime.statistics()["worldImports"] as Number).toLong())
            host.about = false
            frames(runtime)
            assertNotNull(runtime.boundsForTag("miuix-world-source"), "Other pages must restore their world backdrop")
            assertNotNull(runtime.boundsForTag("miuix-world-page-glass"))
            assertTrue((runtime.statistics()["worldImports"] as Number).toLong() > duringTransition)
            assertTrue((runtime.statistics()["worldCrops"] as Number).toLong() > 0)
        }
    }

    @Test
    fun `about progressively blurs scrolling artwork before its surface tint appears`() {
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime)
            val bar = requireNotNull(runtime.boundsForTag("miuix-top-app-bar"))
            // Move the actual bundled brand icon under the lower part of the compact bar.
            // The brand is still visible, so the About surface tint is intentionally absent.
            repeat(20) {
                val logo = requireNotNull(runtime.boundsForTag("about-logo"))
                if ((logo[1] + logo[3]) / 2f > bar[3] - 16f) wheel(runtime, .25f)
            }
            assertTrue("miuix-topbar-expanded" in runtime.getTags(),
                "The brand must still be visible; this regression exercises the untinted bar")
            val logo = requireNotNull(runtime.boundsForTag("about-logo"))
            assertTrue(logo[1] < bar[3] && logo[3] > bar[3] - 30f,
                "The production artwork must cross the bar's progressive band")
            val blurred = capture(frames(runtime))
            fixture.blur = false
            val clear = capture(frames(runtime))
            var changed = 0
            val left = logo[0].toInt().coerceAtLeast(0)
            val right = logo[2].toInt().coerceAtMost(479)
            for (y in (bar[3].toInt() - 34).coerceAtLeast(0) until bar[3].toInt() + 16) {
                for (x in left..right) {
                    val a = blurred.getRGB(x, y)
                    val b = clear.getRGB(x, y)
                    if ((0..2).maxOf { channel ->
                        kotlin.math.abs((a shr (channel * 8) and 255) - (b shr (channel * 8) and 255))
                    } > 8) changed++
                }
            }
            assertTrue(changed > 30,
                "The untinted About bar must progressively blur actual page artwork; changedPixels=$changed")
            for (y in bar[3].toInt() + 20 until bar[3].toInt() + 60) {
                for (x in left..right) assertEquals(clear.getRGB(x, y), blurred.getRGB(x, y),
                    "The top-bar blur must not spread beyond its 16dp feather")
            }
            assertNull(runtime.boundsForTag("miuix-world-source"))
            assertEquals(0L, (runtime.statistics()["worldImports"] as Number).toLong(),
                "About must sample its own page backdrop without importing a hidden world")
            val directory = Path.of(System.getProperty("atomchat.previewDir", "build/native-preview"))
            Files.createDirectories(directory)
            ImageIO.write(blurred, "png", directory.resolve("about-scroll-progressive-blur.png").toFile())
            ImageIO.write(clear, "png", directory.resolve("about-scroll-no-blur.png").toFile())
        }
    }

    @Test
    fun `cache clear still requires the existing confirmation action`() {
        NativeMiuixRuntime(host).use { runtime ->
            frames(runtime)
            reveal(runtime, "setting-cache_clear")
            val before = fixture.actions.size
            click(runtime, "setting-cache_clear")
            frames(runtime)
            assertEquals(before, fixture.actions.size, "Opening a destructive preference must not clear the cache")
            assertNotNull(runtime.boundsForText("Clear cached images?"))
            val confirm = requireNotNull(runtime.boundsForText("Save"))
            point(runtime, (confirm[0] + confirm[2]) / 2f, (confirm[1] + confirm[3]) / 2f)
            assertEquals(Action(Command.SETTINGS_ACTION, "cache_clear"), fixture.actions.last())
        }
    }

    private fun frames(runtime: NativeMiuixRuntime): NativeMiuixRuntime.PixelFrame {
        var frame: NativeMiuixRuntime.PixelFrame? = null
        repeat(24) { clock += 16_666_667; frame = runtime.render(480, 800, 1f, clock) }
        return requireNotNull(frame)
    }
    private fun capture(frame: NativeMiuixRuntime.PixelFrame): BufferedImage {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
        val image = BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until frame.height()) for (x in 0 until frame.width()) {
            image.setRGB(x, y, unsafe.getInt(frame.address() + y.toLong() * frame.rowBytes() + x * 4))
        }
        return image
    }
    private fun wheel(runtime: NativeMiuixRuntime, delta: Float) {
        runtime.pointer(3, 240f, 450f, 0f, delta, -1, 0)
        frames(runtime)
    }
    private fun reveal(runtime: NativeMiuixRuntime, tag: String) {
        repeat(24) {
            val b = runtime.boundsForTag(tag)
            val headerBottom = runtime.boundsForTag("miuix-top-app-bar")?.get(3) ?: 0f
            val center = b?.let { (it[1] + it[3]) / 2f }
            // Bounds are viewport-clipped. The final row can legitimately end at
            // y=800; require a visible clickable center instead of an arbitrary
            // bottom margin that wheel input can never satisfy at the list end.
            if (b != null && b[3] - b[1] >= 24f && center!! > headerBottom + 4f && center < 798f) return
            wheel(runtime, if (b != null && b[1] < headerBottom) -1f else 1f)
        }
        fail<Unit>("Preference $tag was not reachable: bounds=${runtime.boundsForTag(tag)?.contentToString()}; ${runtime.getTags()}")
    }
    private fun click(runtime: NativeMiuixRuntime, tag: String) {
        val b = requireNotNull(runtime.boundsForTag(tag)) { "$tag missing" }
        point(runtime, (b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f)
    }
    private fun point(runtime: NativeMiuixRuntime, x: Float, y: Float) {
        runtime.pointer(1, x, y, 0f, 0f, 0, 0)
        runtime.pointer(2, x, y, 0f, 0f, 0, 0)
        frames(runtime)
    }

    private class AboutHost(private val fixture: NativeMiuixRuntimeTest.FixtureHost) : MiuixHost by fixture {
        var about = true
        var transitionBackdrop = false
        override fun worldBackdropRequired(state: State) = transitionBackdrop || !state.hasOpaquePageBackground()
        val imageKeys = mutableSetOf<String>()
        private val images = mutableMapOf<String, ByteArray>()
        override fun image(key: String): ByteArray? {
            val asset = when (key) {
                "about-logo" -> "logo.png"
                "about-wordmark" -> "logo_wordmark.png"
                "about-author-e33epus" -> "authors/e33epus.png"
                "about-author-nowordz" -> "authors/nowordz.png"
                else -> return fixture.image(key)
            }
            imageKeys.add(key)
            return images.getOrPut(key) {
                val relative = "platforms/26.3-neoforge/src/main/resources/assets/atomchat/$asset"
                val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .map { it.resolve(relative) }.firstOrNull { Files.isRegularFile(it) }
                requireNotNull(file) { "Missing production About asset $asset" }
                Files.readAllBytes(file)
            }
        }
        override fun snapshot(): State {
            val s = fixture.snapshot()
            if (!about) return s
            return State(Route("settings_section", "", "About", "about", true, false, ""),
                s.conversations(), s.messages(), s.composer(),
                Settings("about", emptyList(), listOf(
                    row("version", "Version", "9.8.7-test", "about_version", RELEASES),
                    row("source", "View Source", "GitHub", "about_modinfo", SOURCE),
                    row("license", "License", "MIT", "about_modinfo", LICENSE),
                    row("thirdparty", "Skija", "Skija", "about_thirdparty", THIRD_PARTY),
                    Setting("debug", "switch", "Debug mode", "Diagnostics", "about_advanced", true, false,
                        0f, 0f, 1f, 1f, "", 0, emptyList(), "", false, emptyList(), 0),
                    Setting("cache_clear", "action", "Clear image cache", "Clear cached images?", "about_advanced", true, false,
                        0f, 0f, 0f, 0f, "", 0, emptyList(), "", true, emptyList(), 0),
                    row("credits", "Authors", "E33EPUS · NoWordz", "about_credits", ""),
                ), emptyList(), emptyList()), s.profile(), s.extras(), s.appearance(), false, s.navigationStyle())
        }
        private fun row(id: String, title: String, value: String, group: String, uri: String) =
            Setting(id, "info", title, value, group, true, false, 0f, 0f, 0f, 0f,
                value, 0, emptyList(), uri, false, emptyList(), 0)
    }

    companion object {
        private const val SOURCE = "https://github.com/NoWordz/AtomChat-Mod"
        private const val RELEASES = "$SOURCE/releases"
        private const val LICENSE = "https://opensource.org/licenses/MIT"
        private const val THIRD_PARTY = "https://github.com/HumbleUI/Skija"
    }
}
