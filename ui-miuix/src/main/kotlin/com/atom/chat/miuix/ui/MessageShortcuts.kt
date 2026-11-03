package com.atom.chat.miuix.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Reply
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Compact bubble menu content for an owner-positioned overlay. Keep this mounted
 * until [onDismiss] runs after its exit animation. The owner anchors the measured
 * bounds to the selected bubble and clamps them into the transcript viewport.
 */
@Composable
internal fun MessageShortcuts(
    host: MiuixHost,
    state: MiuixHost.State,
    message: MiuixHost.Message,
    modifier: Modifier = Modifier,
    menu: MessageContextMenuState = remember(message.id()) { MessageContextMenuState() },
    maxHeight: Dp = 264.dp,
    onDismiss: () -> Unit,
) {
    val visibility = remember(menu) { MutableTransitionState(false) }
    val dismissed by rememberUpdatedState(onDismiss)
    SideEffect { visibility.targetState = menu.isOpen }
    LaunchedEffect(visibility.isIdle, visibility.currentState, menu.isOpen) {
        if (visibility.isIdle && !visibility.currentState && !menu.isOpen) dismissed()
    }
    val canReact = message.reactionsAvailable() && state.extras().emoji().isNotEmpty()
    LaunchedEffect(canReact) {
        if (!canReact) menu.showActions()
    }
    val unit = host.uiMetrics().avatarSize() / 40f
    val enterMs = if (state.animations()) 150 else 0
    val exitMs = if (state.animations()) 100 else 0
    AnimatedVisibility(
        visibleState = visibility,
        modifier = modifier.testTag("message-shortcuts-${message.id()}"),
        enter = fadeIn(tween(enterMs)) + scaleIn(tween(enterMs), initialScale = 0.94f,
            transformOrigin = TransformOrigin(0.5f, 0f)),
        exit = fadeOut(tween(exitMs)) + scaleOut(tween(exitMs), targetScale = 0.94f,
            transformOrigin = TransformOrigin(0.5f, 0f)),
    ) {
        Card(Modifier.heightIn(max = maxHeight), insideMargin = PaddingValues(4.dp)) {
            AnimatedContent(
                targetState = menu.reactionsOpen && canReact,
                transitionSpec = {
                    (fadeIn(tween(enterMs)) togetherWith fadeOut(tween(exitMs))).using(
                        SizeTransform(sizeAnimationSpec = { _, _ -> tween(enterMs) }),
                    )
                },
            ) { reactions ->
                if (reactions) {
                    ReactionMenu(host, state, message, menu)
                } else {
                    Column(Modifier.width((110f * unit - 8f).dp).verticalScroll(rememberScrollState())) {
                        val enabled = menu.isOpen && !menu.reactionsOpen
                        MessageMenuAction(host.label("atomchat.context.copy"), MiuixIcons.Copy, enabled, unit) {
                            if (menu.isOpen) {
                                host.action(Command.COPY_MESSAGE, message.id().toString())
                                menu.dismiss()
                            }
                        }
                        if (!state.route().readOnly()) {
                            MessageMenuAction(host.label("atomchat.context.quote"), MiuixIcons.Reply, enabled, unit) {
                                if (menu.isOpen) {
                                    host.action(Command.REPLY, message.id().toString())
                                    menu.dismiss()
                                }
                            }
                        }
                        if (!message.imageKey().isNullOrEmpty()) {
                            MessageMenuAction(host.label("atomchat.context.save"), MiuixIcons.Download, enabled, unit) {
                                if (menu.isOpen) {
                                    host.action(Command.SAVE_IMAGE, message.imageKey())
                                    menu.dismiss()
                                }
                            }
                        }
                        if (canReact) {
                            MessageMenuAction(host.label("atomchat.context.react"), AtomChatIcons.Emoji, enabled, unit) {
                                menu.showReactions()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionMenu(
    host: MiuixHost,
    state: MiuixHost.State,
    message: MiuixHost.Message,
    menu: MessageContextMenuState,
) {
    Column(Modifier.width(240.dp)) {
        MessageMenuAction(host.label("gui.back"), MiuixIcons.Back, menu.isOpen && menu.reactionsOpen,
            host.uiMetrics().avatarSize() / 40f) {
            menu.showActions()
        }
        // Matches EmojiPanel.openReaction: mount the picker only on demand;
        // LazyVerticalGrid only composes the cells within its bounded viewport.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(36.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 216.dp)
                .testTag("message-reaction-picker-${message.id()}"),
            contentPadding = PaddingValues(2.dp),
        ) {
            items(state.extras().emoji()) { emoji ->
                TextButton(
                    text = emoji,
                    onClick = {
                        if (menu.isOpen && menu.reactionsOpen && message.reactionsAvailable()) {
                            host.action(Command.REACT, message.id().toString(), emoji)
                            menu.dismiss()
                        }
                    },
                    enabled = menu.isOpen && menu.reactionsOpen && message.reactionsAvailable(),
                    minWidth = 0.dp,
                    minHeight = 36.dp,
                    insideMargin = PaddingValues(4.dp),
                    colors = ButtonDefaults.textButtonColors(
                        color = Color.Transparent,
                        disabledColor = Color.Transparent,
                        disabledTextColor = MiuixTheme.colorScheme.onSecondaryVariant,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MessageMenuAction(
    label: String, icon: ImageVector, enabled: Boolean, unit: Float, onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        minWidth = 0.dp,
        minHeight = (32f * unit).dp,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            color = Color.Transparent,
            // Closing only locks input; the whole menu already fades as one layer.
            // Stock disabled button colors would paint every outgoing row gray.
            disabledColor = Color.Transparent,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            disabledContentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size((16f * unit).dp))
            Text(label, style = MiuixTheme.textStyles.button)
        }
    }
}
