package com.atom.chat.miuix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlurEffect
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Records only the unblurred, panel-cropped world image supplied by the render runtime.
 * Keep this source a sibling of its blur consumers; capturing those consumers would
 * make the backdrop sample itself. Scrolling page content uses a separate backdrop.
 */
@Composable
internal fun WorldSource(image: Image?, backdrop: LayerBackdrop, modifier: Modifier = Modifier) {
    val surface = MiuixTheme.colorScheme.surface.copy(alpha = 1f)
    Canvas(modifier.fillMaxSize().layerBackdrop(backdrop)) {
        // Opaque capture prevents Gaussian spreading into transparent image margins.
        drawRect(surface)
        if (image != null) drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawImageRect(image, Rect.makeWH(size.width, size.height))
        }
    }
}

/** Theme tint and real Miuix Screen/Overlay blending leave the sampled world visible. */
@Composable
internal fun Modifier.mixedChatGlass(backdrop: LayerBackdrop, enabled: Boolean, opacity: Float = 1f): Modifier {
    val tintOpacity = opacity.coerceIn(0f, 1f)
    val colors = MiuixTheme.colorScheme
    val panel = colors.surface.copy(alpha = 1f)
    val dark = panel.luminance() < 0.5f
    val mixes = BlurDefaults.blurColors(
        blendColors = listOf(
            BlendColorEntry(panel.copy(alpha = 0.55f * tintOpacity)),
            BlendColorEntry(
                (if (dark) colors.surfaceContainer else Color.White).copy(alpha = 0.20f * tintOpacity),
                BlurBlendMode.Screen,
            ),
            BlendColorEntry(colors.surfaceContainer.copy(alpha = 0.18f * tintOpacity), BlurBlendMode.Overlay),
        ),
        saturation = 1.1f,
    )
    return if (enabled) textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = 18f,
        noiseCoefficient = 0f,
        colors = mixes,
    ) else background(panel.copy(alpha = tintOpacity))
}

/**
 * Snapshot of Hypernotesuper's MiuixBlurredBar progressive top bar. Keep its
 * backdrop outside TopAppBar's clip, and extend only the drawing by 16 dp so
 * the clear end feathers into the page without moving the content or hit area.
 */
@Composable
internal fun TopChatGlass(
    backdrop: LayerBackdrop,
    enabled: Boolean,
    collapseProgress: () -> Float = { 0f },
    compact: Boolean = false,
    surfaceVisible: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val surface = colors.surface
    val dimColor = if (surface.luminance() < 0.5f) surface else colors.onSurface
    val gradient = remember {
        ProgressiveBlur.Top.copy(startFraction = 0.15f, endFraction = 1f, curve = 1.25f)
    }
    val radius = minOf(18f, 52f / LocalDensity.current.density)
    // An untinted About bar still samples and progressively blurs the page artwork.
    // surfaceVisible controls its reading-surface tint, not the blur itself.
    val glass = if (enabled) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { RectangleShape },
        progressiveGradient = gradient,
        effects = {
            // Reading here keeps scrolling in the draw phase instead of recomposition.
            val collapsed = collapseProgress().coerceIn(0f, 1f)
            progressiveTextureBlurEffect(
                blurRadiusX = radius,
                gradient = gradient,
                noiseCoefficient = 0f,
                colors = BlurColors(
                    blendColors = if (!surfaceVisible) emptyList() else listOf(
                        // A chat's pinned title needs a stable reading surface as messages
                        // pass underneath; its tint must not disappear at a scroll boundary.
                        BlendColorEntry(surface.copy(alpha = if (compact) 0.88f else 0.52f * (1f - collapsed))),
                        BlendColorEntry(dimColor.copy(alpha = if (compact) 0f else 0.14f * collapsed), BlurBlendMode.Multiply),
                    ),
                ),
            )
        },
    ) else if (surfaceVisible) Modifier.background(surface) else Modifier
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier.matchParentSize()
                .then(if (enabled) Modifier.layout { measurable, constraints ->
                    val featherHeight = 16.dp.roundToPx()
                    val placeable = measurable.measure(constraints.offset(vertical = featherHeight))
                    layout(placeable.width, placeable.height - featherHeight) {
                        placeable.placeRelative(0, 0)
                    }
                } else Modifier)
                .then(glass),
        )
        content()
    }
}

/**
 * Upstream progressiveTextureBlur supplies both its multilevel blur and full-resolution
 * clear-end pass. The blur radius genuinely changes through the band; this is not an
 * alpha fade over a uniformly blurred image. Tint fades out with the upstream effect.
 */
@Composable
internal fun Modifier.progressiveChatGlass(
    backdrop: LayerBackdrop,
    bottom: Boolean,
    enabled: Boolean,
    opacity: Float = 1f,
): Modifier {
    val surface = MiuixTheme.colorScheme.surface
    val gradient = remember(bottom) {
        (if (bottom) ProgressiveBlur.Bottom else ProgressiveBlur.Top)
            .copy(startFraction = 0.15f, endFraction = 1f, curve = 1.25f)
    }
    val mixes = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.52f * opacity.coerceIn(0f, 1f)))),
        saturation = 1.1f,
    )
    // Matches the reference's 18 dp radius with a 52 physical-pixel ceiling.
    val radius = minOf(18f, 52f / LocalDensity.current.density)
    return if (enabled) progressiveTextureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = radius,
        gradient = gradient,
        noiseCoefficient = 0f,
        colors = mixes,
    ) else this
}
