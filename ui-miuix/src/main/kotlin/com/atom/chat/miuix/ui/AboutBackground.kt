// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from example/shared/src/commonMain/kotlin/component/effect/BgEffectBackground.kt
// and BgEffectModifier.kt; retains the upstream OS3 shader, presets and color spring.
package com.atom.chat.miuix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.ui.effect.BgEffectConfig
import com.atom.chat.miuix.ui.effect.BgEffectPainter
import com.atom.chat.miuix.ui.effect.DeviceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.floor

/** The official OS3 about background; mounted only by the visible about page. */
@Composable
internal fun AboutBackground(progress: () -> Float, animations: Boolean) {
    val surface = MiuixTheme.colorScheme.surface
    val dark = surface.luminance() < .5f
    // Compile once per mounted background. Unsupported backends keep the solid surface.
    val painter = remember {
        if (isRuntimeShaderSupported()) runCatching {
            BgEffectPainter().also { it.runtimeShader }
        }.getOrNull() else null
    }
    DisposableEffect(painter) {
        onDispose { painter?.close() }
    }
    val currentProgress by rememberUpdatedState(progress)
    val visible by remember { derivedStateOf { currentProgress() < 1f } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("about-official-background")) {
        val device = if (maxWidth >= 840.dp) DeviceType.PAD else DeviceType.PHONE
        val preset = remember(device, dark) { BgEffectConfig.get(device, dark) }
        val colorStage = remember { Animatable(0f) }
        var elapsed by remember { mutableFloatStateOf(0f) }
        var emittedColorStage by remember { mutableFloatStateOf(0f) }
        val playing = animations && visible && painter != null
        LaunchedEffect(playing, preset) {
            if (!playing) return@LaunchedEffect
            launch {
                var target = floor(colorStage.value) + 1f
                while (isActive) {
                    delay((preset.colorInterpPeriod * 500).toLong())
                    colorStage.animateTo(target, spring(dampingRatio = .9f, stiffness = 35f))
                    target += 1f
                }
            }
            val previousElapsed = elapsed
            val origin = withFrameNanos { it }
            var previous = origin
            val interval = 1_000_000_000L / 60L
            while (isActive) {
                val now = withFrameNanos { it }
                if (now - previous < interval) continue
                // Keep phase during ordinary frame jitter without bursting after a pause.
                previous = if (now - previous >= interval * 4) now else previous + interval
                elapsed = previousElapsed + (now - origin) / 1_000_000_000f
                emittedColorStage = colorStage.value
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(surface)
            val alpha = (1f - currentProgress()).coerceIn(0f, 1f)
            if (painter != null && alpha > 0f && size.width > 0f && size.height > 0f) {
                painter.updateResolution(size.width, size.height)
                // Match the official About page's default full-background setting.
                painter.updateBoundIfNeeded(size.height * .8f, size.height, size.width)
                painter.updatePresetIfNeeded(device, dark)
                painter.updateColors(preset, emittedColorStage)
                painter.updateAnimTime(elapsed)
                painter.updatePointsAnim(elapsed, preset)
                drawRect(painter.brush, alpha = alpha)
            }
        }
    }
}
