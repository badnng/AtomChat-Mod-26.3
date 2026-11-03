package com.atom.chat.miuix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import kotlin.math.roundToInt

/** A compact overlay anchored to the selected bubble, never to the mouse cursor. */
@Composable
internal fun MessageMenuOverlay(
    host: MiuixHost, state: MiuixHost.State, message: MiuixHost.Message,
    anchor: Rect?, padding: PaddingValues, menu: MessageContextMenuState,
    onBounds: (Rect) -> Unit, onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var measured by remember(menu) { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }
        .onGloballyPositioned { coordinates = it }) {
        if (viewport.width > 0 && viewport.height > 0) {
            val margin = with(density) { 8.dp.toPx() }
            val left = with(density) { padding.calculateLeftPadding(direction).toPx() } + margin
            val right = (viewport.width - with(density) { padding.calculateRightPadding(direction).toPx() } - margin)
                .coerceAtLeast(left + 1f)
            // The top bar feathers sixteen dp beyond its measured height.
            val top = with(density) { (padding.calculateTopPadding() + 16.dp).toPx() }
            val bottom = (viewport.height - with(density) { padding.calculateBottomPadding().toPx() } - margin)
                .coerceAtLeast(top + 1f)
            val owner = coordinates?.takeIf { it.isAttached }
            val local = if (anchor != null && owner != null) {
                Rect(owner.localPositionOf(owner.findRootCoordinates(), anchor.topLeft),
                    owner.localPositionOf(owner.findRootCoordinates(), anchor.bottomRight))
            } else Rect(left, top, left, top)
            val unit = host.uiMetrics().avatarSize() / 40f
            val estimatedWidth = with(density) { (if (menu.reactionsOpen) 248.dp else (110f * unit).dp).toPx() }
            val width = (if (measured.width > 0) measured.width.toFloat() else estimatedWidth).coerceAtMost(right - left)
            val rows = 1 + (if (!state.route().readOnly()) 1 else 0) +
                (if (!message.imageKey().isNullOrEmpty()) 1 else 0) +
                (if (message.reactionsAvailable() && state.extras().emoji().isNotEmpty()) 1 else 0)
            val estimatedHeight = with(density) {
                (if (menu.reactionsOpen) 264.dp else (rows * 32f * unit + 8f).dp).toPx()
            }
            val height = (if (measured.height > 0) measured.height.toFloat() else estimatedHeight).coerceAtMost(bottom - top)
            val beside = if (message.own()) local.left - width - margin else local.right + margin
            val preferredX = if (beside >= left && beside + width <= right) beside
                else if (message.own()) local.right - width else local.left
            val below = local.bottom + margin
            val preferredY = if (below + height <= bottom) below
                else if (local.top - height - margin >= top) local.top - height - margin else local.top
            val position = IntOffset(preferredX.coerceIn(left, (right - width).coerceAtLeast(left)).roundToInt(),
                preferredY.coerceIn(top, (bottom - height).coerceAtLeast(top)).roundToInt())
            MessageShortcuts(host, state, message, menu = menu,
                modifier = Modifier.offset { position }
                    .widthIn(max = with(density) { (right - left).toDp() })
                    .onSizeChanged { if (it.width > 0 && it.height > 0) measured = it }
                    .onGloballyPositioned { onBounds(it.boundsInRoot()) },
                maxHeight = with(density) { (bottom - top).toDp() }, onDismiss = onDismiss)
        }
    }
}
