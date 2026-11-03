package com.atom.chat.miuix.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import kotlin.math.hypot
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Overlay in the bubble's Box; all coordinates are local physical pixels.
 * [anchor] is the last visible glyph's bottom-right, or an image/content fallback.
 * The owner reserves 26 dp beside/below that point and observes [pointer] without
 * consuming events. The footprint and proximity region never depend on opacity.
 */
@Composable
internal fun BoxScope.MessageCopyButton(
    host: MiuixHost,
    messageId: String,
    anchor: Offset,
    pointer: Offset?,
    availableSize: IntSize,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val diameter = 24.dp
    val diameterPx = with(density) { diameter.toPx() }
    val gapPx = with(density) { 2.dp.toPx() }
    val proximityPx = with(density) { 36.dp.toPx() }
    val maxX = (availableSize.width - diameterPx).coerceAtLeast(0f)
    val maxY = (availableSize.height - diameterPx).coerceAtLeast(0f)
    val x = if (anchor.x.isFinite()) (anchor.x + gapPx).coerceIn(0f, maxX) else maxX
    val y = if (anchor.y.isFinite()) (anchor.y + gapPx).coerceIn(0f, maxY) else maxY
    val position = IntOffset(x.roundToInt(), y.roundToInt())
    val hasBounds = availableSize.width > 0 && availableSize.height > 0
    val nearby = hasBounds && pointer != null && pointer.x.isFinite() && pointer.y.isFinite() && run {
        val dx = (pointer.x - position.x).coerceIn(0f, diameterPx) - (pointer.x - position.x)
        val dy = (pointer.y - position.y).coerceIn(0f, diameterPx) - (pointer.y - position.y)
        hypot(dx, dy) <= proximityPx
    }
    var focused by remember(messageId) { mutableStateOf(false) }
    val revealed = hasBounds && (nearby || focused)
    val animations = LocalAnimationsEnabled.current
    val animatedAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = if (animations) tween(durationMillis = 150) else snap(),
        label = "message-copy-alpha",
    )
    val alpha = if (animations) animatedAlpha else if (revealed) 1f else 0f
    // Drop pointer handling as soon as proximity/focus ends, including fade-out.
    val interactive = revealed && alpha > 0f
    val copyLabel = host.label("atomchat.context.copy")
    val copy = { host.action(Command.COPY_MESSAGE, target = messageId) }

    Box(
        modifier = modifier
            .offset { position }
            .size(diameter)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val activates = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar
                if (!focused || !hasBounds || !activates) false
                else {
                    if (event.type == KeyEventType.KeyUp) copy()
                    true
                }
            }
            .semantics {
                contentDescription = copyLabel
                role = Role.Button
                // A semantics action also provides the accessible path before hover.
                onClick(label = copyLabel) { copy(); true }
            }
            .focusable(enabled = hasBounds)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainer),
    ) {
        IconButton(
            onClick = copy,
            enabled = interactive,
            modifier = Modifier.matchParentSize().focusProperties { canFocus = false },
            backgroundColor = Color.Transparent,
            minWidth = diameter,
            minHeight = diameter,
        ) {
            Icon(
                imageVector = MiuixIcons.Copy,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}
