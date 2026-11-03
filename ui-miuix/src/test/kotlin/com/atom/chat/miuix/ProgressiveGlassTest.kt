@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.atom.chat.miuix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.atom.chat.miuix.ui.WorldSource
import com.atom.chat.miuix.ui.progressiveChatGlass
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import javax.imageio.ImageIO
import kotlin.coroutines.CoroutineContext
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ProgressiveGlassTest {
    @Test
    fun `official progressive effect changes frequency response through its band`() {
        val width = 480
        val height = 800
        // Both halves repeat identical rows. Left: high spatial frequency, right: low.
        // A uniform blur plus an alpha/tint gradient preserves the frequency ratio.
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val period = if (x < width / 2) 6 else 48
            val level = if ((x / period) % 2 == 0) 255.toByte() else 0.toByte()
            val offset = (y * width + x) * 4
            rgba[offset] = level
            rgba[offset + 1] = level
            rgba[offset + 2] = level
            rgba[offset + 3] = 255.toByte()
        }
        val pending = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
        val recomposer = FrameRecomposer(dispatcher) {}
        val scene = CanvasLayersComposeScene(frameRecomposer = recomposer, platformContext = PlatformContext.Empty())
        try {
            Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), rgba, width * 4).use { source ->
                Surface.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)).use { surface ->
                    scene.density = Density(1f)
                    scene.size = IntSize(width, height)
                    scene.setContent {
                        MiuixTheme {
                            val backdrop = rememberLayerBackdrop()
                            Box(Modifier.fillMaxSize()) {
                                WorldSource(source, backdrop)
                                Box(Modifier.fillMaxSize().progressiveChatGlass(backdrop, bottom = false, enabled = true))
                            }
                        }
                    }
                    repeat(24) { frame ->
                        drain()
                        recomposer.performFrame(1_000_000_000L + frame * 16_666_667L)
                        drain()
                        scene.measureAndLayout()
                        surface.canvas.clear(0)
                        scene.draw(surface.canvas.asComposeCanvas())
                    }
                    val bytes = surface.makeImageSnapshot().use { image ->
                        image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                    }
                    val image = ImageIO.read(ByteArrayInputStream(bytes))
                    val directory = Path.of(System.getProperty("atomchat.previewDir", "build/native-preview"))
                    Files.createDirectories(directory)
                    Files.write(directory.resolve("progressive-frequency-response.png"), bytes)
                    fun contrast(y: Int, start: Int, end: Int): Double {
                        val samples = (start until end).map { x -> (image.getRGB(x, y) shr 16 and 255).toDouble() }
                        val mean = samples.average()
                        return sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size)
                    }
                    val blurredHigh = contrast(40, 30, 210)
                    val blurredLow = contrast(40, 280, 460)
                    val clearHigh = contrast(740, 30, 210)
                    val clearLow = contrast(740, 280, 460)
                    assertTrue(blurredLow > 1 && clearLow > 1, "The backdrop was not sampled: $blurredLow / $clearLow")
                    val blurredRatio = blurredHigh / blurredLow
                    val clearRatio = clearHigh / clearLow
                    assertTrue(clearRatio > blurredRatio + 0.2,
                        "Progressive blur must change spatial frequency response, not only alpha: top=$blurredRatio bottom=$clearRatio")
                }
            }
        } finally {
            scene.close()
            recomposer.close()
        }
    }
}
