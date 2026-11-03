package com.atom.chat.miuix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalModalCount = staticCompositionLocalOf { mutableIntStateOf(0) }

@Composable
internal fun ModalRegistration(show: Boolean) {
    val count = LocalModalCount.current
    DisposableEffect(show) {
        if (show) count.intValue++
        onDispose { if (show) count.intValue-- }
    }
}

/** Domain-owned queues and expiry are shared with the default interface. */
@Composable
internal fun NoticeOverlay(host: MiuixHost, state: MiuixHost.State, padding: PaddingValues) {
    if (LocalModalCount.current.intValue != 0 || state.extras().cropEditor() != null) return
    // This overlay is drawn above Scaffold's bars. Use the real available height:
    // a 240 dp viewport cuts the last banner (and its squircle) in a three-notice stack.
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(padding).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        items(state.extras().notices(), key = { it.id() }) { notice ->
            val motion = if (state.animations()) Modifier.animateItem(
                fadeInSpec = tween(220),
                placementSpec = spring(dampingRatio = 0.9f, stiffness = 400f),
                fadeOutSpec = tween(180),
            ) else Modifier
            NoticeBanner(host, notice, state.animations(), motion)
        }
    }
}

@Composable
private fun NoticeBanner(host: MiuixHost, notice: MiuixHost.Notice, animations: Boolean, modifier: Modifier) {
    // Only presentation progress is local: the host still owns order, lifetime and removal.
    // Animatable and animateItem both inherit the scene's MotionDurationScale.
    val arrival = remember(notice.id()) { Animatable(if (animations) 0f else 1f) }
    LaunchedEffect(notice.id(), animations) {
        if (animations) arrival.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 400f))
        else arrival.snapTo(1f)
    }
    Card(modifier.fillMaxWidth().graphicsLayer {
        // Keep the entering shape inside its lazy viewport so the first banner's
        // upper corners remain intact throughout the animation.
        val progress = (if (animations) arrival.value else 1f).coerceIn(0f, 1f)
        alpha = progress
        scaleX = .96f + .04f * progress
        scaleY = scaleX
    }, insideMargin = PaddingValues(12.dp),
        onClick = { host.action(Command.NOTICE_OPEN, notice.id()) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayerAvatar(host, notice.player(), 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(runs(host, notice.senderRuns(), notice.sender(), 0L),
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                Text(runs(host, notice.contentRuns(), notice.content(), 0L), maxLines = 2)
            }
            IconButton(onClick = { host.action(Command.NOTICE_REPLY, notice.id()) }) {
                Icon(MiuixIcons.Send, host.label("atomchat.context.quote"))
            }
        }
    }
}
