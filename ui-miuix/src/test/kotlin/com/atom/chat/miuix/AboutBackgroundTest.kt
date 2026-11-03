@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.atom.chat.miuix

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.hasInvalidations
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.atom.chat.miuix.ui.AboutBackground
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
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Exercises the actual official shader and mounted Compose frame-clock lifecycle. */
class AboutBackgroundTest {
    @Test
    fun `official background animates only while visible and enabled in both game color modes`() {
        for (dark in listOf(false, true)) verifyBackground(dark)
    }

    private fun verifyBackground(dark: Boolean) {
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
        val recomposer = FrameRecomposer(dispatcher) {}
        val scene = CanvasLayersComposeScene(frameRecomposer = recomposer, platformContext = PlatformContext.Empty())
        val animations = mutableStateOf(false)
        val mounted = mutableStateOf(true)
        val progress = mutableFloatStateOf(0f)
        var nanos = 1_000_000_000L
        try {
            Surface.makeRaster(ImageInfo(320, 480, ColorType.BGRA_8888, ColorAlphaType.PREMUL)).use { surface ->
                scene.density = Density(1f)
                scene.size = IntSize(320, 480)
                scene.setContent {
                    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                        if (mounted.value) AboutBackground({ progress.floatValue }, animations.value)
                        else Spacer(Modifier.fillMaxSize())
                    }
                }
                fun frames(count: Int = 12) {
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
                }
                fun image(): java.awt.image.BufferedImage {
                    val png = surface.makeImageSnapshot().use { snapshot ->
                        snapshot.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                    }
                    return ImageIO.read(ByteArrayInputStream(png))
                }
                fun pixels() = image().getRGB(0, 0, 320, 480, null, 0, 320)
                fun idle(message: String) {
                    drain()
                    assertFalse(recomposer.hasPendingWork(), "$message: frame clock still has pending work")
                    assertFalse(scene.hasInvalidations(), "$message: background still invalidates the scene")
                }
                frames()
                val still = pixels()
                assertTrue(still.toSet().size > 100, "Official shader must produce colored artwork, not only fallback surface")
                frames(90)
                assertArrayEquals(still, pixels(), "Disabled animation must preserve the exact rendered background")
                idle("Disabled animation")

                animations.value = true
                frames(60)
                val moving = pixels()
                assertFalse(still.contentEquals(moving), "Enabled official background must change pixels as the scene clock advances")
                val previews = Path.of(System.getProperty("atomchat.previewDir", "build/native-preview"))
                Files.createDirectories(previews)
                ImageIO.write(image(), "png", previews.resolve("about-official-background-${if (dark) "dark" else "light"}.png").toFile())

                animations.value = false
                frames()
                val paused = pixels()
                frames(90)
                assertArrayEquals(paused, pixels(), "Turning animations off must freeze the current artwork")
                idle("Animation turned off")

                animations.value = true
                progress.floatValue = 1f
                frames()
                assertEquals(1, pixels().toSet().size, "Fully scrolled artwork must fade to the stock surface")
                idle("Fully scrolled about background")
                progress.floatValue = 0f
                frames(30)
                assertTrue(pixels().toSet().size > 100, "Returning to the hero must restore the official artwork")
                mounted.value = false
                frames()
                idle("Leaving the about page")
            }
        } finally {
            scene.close()
            recomposer.close()
            drain()
        }
    }
}
