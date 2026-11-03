@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.atom.chat.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.*
import com.atom.chat.miuix.ui.PageNavigation
import java.io.ByteArrayInputStream
import java.util.ArrayDeque
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Pixel evidence for the shipping shared navigation, without rendering full app pages. */
class NativePopCornersTest {
    @Test
    fun `pop moves rounded page corners while the fixed outer panel remains clipped`() {
        val fixture = NativeMiuixRuntimeTest.FixtureHost()
        val secondary = mutableStateOf(true)
        var navigation = Navigation(0L, "NONE")
        val host = object : MiuixHost by fixture {
            override fun navigation() = navigation
            override fun snapshot(): State {
                val s = fixture.snapshot()
                val route = if (secondary.value) Route("settings_section", "", "Appearance", "appearance", true, false, "")
                    else Route("settings", "", "Settings", "", false, false, "")
                return State(route, s.conversations(), s.messages(), s.composer(), s.settings(), s.profile(), s.extras(),
                    s.appearance(), true, s.navigationStyle())
            }
        }
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
        val recomposer = FrameRecomposer(dispatcher) {}
        val scene = CanvasLayersComposeScene(frameRecomposer = recomposer, platformContext = PlatformContext.Empty())
        val width = 160
        val height = 240
        val radius = fixture.snapshot().appearance().cornerRadius().dp
        var exitingOrigin = Offset.Zero
        var nanos = 1_000_000_000L
        try {
            Surface.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)).use { surface ->
                scene.density = Density(1f)
                scene.size = IntSize(width, height)
                scene.setContent {
                    MiuixTheme {
                        Box(Modifier.fillMaxSize().squircleClip(radius)) {
                            PageNavigation(host, host.snapshot()) { _, state ->
                                val exiting = state.route().page() == "settings_section"
                                Box(Modifier.fillMaxSize().background(if (exiting) Color.Red else Color.Blue)
                                    .onGloballyPositioned { if (exiting) exitingOrigin = it.localToRoot(Offset.Zero) })
                            }
                        }
                    }
                }
                fun render(count: Int): java.awt.image.BufferedImage {
                    repeat(count) {
                        nanos += 16_666_667L
                        Snapshot.sendApplyNotifications()
                        drain()
                        recomposer.performFrame(nanos)
                        drain()
                        scene.measureAndLayout()
                        surface.canvas.clear(0)
                        scene.draw(surface.canvas.asComposeCanvas())
                    }
                    val bytes = surface.makeImageSnapshot().use { snapshot ->
                        snapshot.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                    }
                    return ImageIO.read(ByteArrayInputStream(bytes))
                }
                render(4)
                navigation = Navigation(1L, "POP")
                secondary.value = false
                val moving = render(8)
                val edge = exitingOrigin.x.toInt() + 2
                assertTrue(edge in 8 until width - 30, "Probe must sample a real intermediate POP position: $exitingOrigin")
                fun red(x: Int, y: Int) = moving.getRGB(x, y) shr 16 and 255
                fun blue(x: Int, y: Int) = moving.getRGB(x, y) and 255
                assertTrue(red(edge, height / 2) > 220 && blue(edge, height / 2) < 20,
                    "The outgoing page body must remain opaque at its translated edge")
                for (y in listOf(2, height - 3)) {
                    assertTrue(blue(edge, y) > 200 && red(edge, y) < 40,
                        "The moving page corner must expose the underlying page, not a square outgoing corner at ($edge,$y)")
                }
                assertEquals(0, moving.getRGB(0, 0) ushr 24, "The fixed outer panel corner must remain transparent during POP")
                val settled = render(34)
                assertEquals(0, settled.getRGB(0, 0) ushr 24, "POP completion must retain the outer panel mask")
                assertTrue((settled.getRGB(width / 2, height / 2) and 255) > 240,
                    "The real incoming page must occupy the panel after POP")
            }
        } finally {
            scene.close()
            recomposer.close()
        }
    }
}
