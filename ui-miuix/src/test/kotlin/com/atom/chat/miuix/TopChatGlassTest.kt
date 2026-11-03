@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.atom.chat.miuix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.ui.TopChatGlass
import com.atom.chat.miuix.ui.WorldSource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

class TopChatGlassTest {
    @Test
    fun `collapse dims the sampled topbar without recomposing or moving content`() {
        val width = 240
        val height = 200
        val pixels = ByteArray(width * height * 4) { index -> if (index % 4 == 3) 255.toByte() else 100.toByte() }
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
        val recomposer = FrameRecomposer(dispatcher) {}
        val scene = CanvasLayersComposeScene(frameRecomposer = recomposer, platformContext = PlatformContext.Empty())
        val collapsed = mutableFloatStateOf(0f)
        val chat = mutableStateOf(false)
        val surfaceVisible = mutableStateOf(true)
        val blurEnabled = mutableStateOf(true)
        var compositions = 0
        var nextContentY = -1f
        try {
            Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), pixels, width * 4).use { source ->
                Surface.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)).use { surface ->
                    scene.density = Density(1f)
                    scene.size = IntSize(width, height)
                    scene.setContent {
                        MiuixTheme {
                            SideEffect { compositions++ }
                            val backdrop = rememberLayerBackdrop()
                            Box(Modifier.fillMaxSize()) {
                                WorldSource(source, backdrop)
                                Column(Modifier.fillMaxWidth()) {
                                    TopChatGlass(backdrop, enabled = blurEnabled.value,
                                        collapseProgress = { collapsed.floatValue }, compact = chat.value,
                                        surfaceVisible = surfaceVisible.value) {
                                        Spacer(Modifier.fillMaxWidth().height(120.dp))
                                    }
                                    Spacer(Modifier.height(1.dp).onGloballyPositioned { nextContentY = it.positionInRoot().y })
                                }
                            }
                        }
                    }
                    var frame = 0
                    fun render(): java.awt.image.BufferedImage {
                        repeat(12) {
                            drain()
                            recomposer.performFrame(1_000_000_000L + frame++ * 16_666_667L)
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
                    val expanded = render()
                    val before = compositions
                    collapsed.floatValue = 1f
                    Snapshot.sendApplyNotifications()
                    val compact = render()
                    val previews = Path.of(System.getProperty("atomchat.previewDir", "build/native-preview"))
                    Files.createDirectories(previews)
                    ImageIO.write(expanded, "png", previews.resolve("top-glass-expanded.png").toFile())
                    ImageIO.write(compact, "png", previews.resolve("top-glass-collapsed.png").toFile())
                    fun red(image: java.awt.image.BufferedImage, y: Int) = image.getRGB(width / 2, y) shr 16 and 255
                    assertTrue(red(expanded, 20) > red(compact, 20) + 20,
                        "Collapse should replace the light surface tint with Multiply dimming")
                    assertEquals(before, compositions, "Collapse progress must be read only while drawing")
                    assertEquals(120f, nextContentY, "The 16 dp feather must not add layout height")
                    // The library smoothsteps its tint ramp twice; late feather pixels
                    // round back to the source in an 8-bit image. Inspect the whole
                    // out-of-layout band rather than assuming y=122 stays distinguishable.
                    assertTrue((120 until 136).any { red(expanded, it) > red(expanded, 145) },
                        "The progressive background must feather below the measured topbar: " +
                            (110..145).joinToString { "$it=${red(expanded, it)}" })
                    assertTrue((136..145).all { red(expanded, it) == 100 && red(compact, it) == 100 },
                        "Pixels beyond the feather must remain untouched")

                    chat.value = true
                    collapsed.floatValue = 0f
                    Snapshot.sendApplyNotifications()
                    val pinnedExpanded = render()
                    collapsed.floatValue = 1f
                    Snapshot.sendApplyNotifications()
                    val pinnedCollapsed = render()
                    assertEquals(red(pinnedExpanded, 20), red(pinnedCollapsed, 20),
                        "A pinned chat reading surface must not fade as content scrolls")
                    assertTrue(red(pinnedCollapsed, 20) > red(compact, 20) + 50,
                        "The compact chat must mask passing content with its stronger surface tint")
                    surfaceVisible.value = false
                    Snapshot.sendApplyNotifications()
                    assertEquals(100, red(render(), 20),
                        "An untinted About bar must preserve a uniform backdrop's source color")
                    blurEnabled.value = false
                    Snapshot.sendApplyNotifications()
                    assertEquals(100, red(render(), 20),
                        "An untinted About bar must also omit the solid shader fallback")
                    drain()
                    assertFalse(scene.hasInvalidations(), "A settled topbar must not request endless redraws")
                    assertFalse(recomposer.hasPendingWork(), "A settled topbar must not keep scheduling frames")
                }
            }
        } finally {
            scene.close()
            recomposer.close()
        }
    }
}
