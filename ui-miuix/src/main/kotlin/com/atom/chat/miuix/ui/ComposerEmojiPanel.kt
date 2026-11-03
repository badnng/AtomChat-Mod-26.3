package com.atom.chat.miuix.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image as SkiaImage
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.springAnimateToPage

internal fun Modifier.composerPopupAnchor(onBounds: (Rect) -> Unit): Modifier =
    onGloballyPositioned { onBounds(it.boundsInWindow()) }

/** Always opens above the complete composer, including its tools and reply preview. */
private class AboveComposerPositionProvider(private val composerBounds: Rect) : PopupPositionProvider {
    override fun getMargins() = PaddingValues(vertical = 8.dp)

    override fun calculatePosition(anchorBounds: IntRect, windowBounds: IntRect,
                                   layoutDirection: LayoutDirection, popupContentSize: IntSize,
                                   popupMargin: IntRect, alignment: PopupPositionProvider.Align): IntOffset {
        // boundsInWindow and the Miuix windowBounds use the same window coordinate space.
        // Keep the measured composer anchor even if an intermediate layout is introduced later.
        val x = if (layoutDirection == LayoutDirection.Rtl) composerBounds.right.toInt() - popupContentSize.width
                else composerBounds.left.toInt()
        return IntOffset(
            x.coerceIn(windowBounds.left, (windowBounds.right - popupContentSize.width).coerceAtLeast(windowBounds.left)),
            (composerBounds.top.toInt() - popupContentSize.height - popupMargin.top).coerceAtLeast(windowBounds.top))
    }
}

@Composable
internal fun ComposerExtrasPopup(show: Boolean, anchorBounds: Rect, onDismiss: () -> Unit,
                                 onDismissFinished: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val positionProvider = remember(anchorBounds) { AboveComposerPositionProvider(anchorBounds) }
    val width = with(density) { anchorBounds.width.toDp() }.coerceAtLeast(1.dp)
    // Shrink the content to the space above the anchor; never move it over the input.
    val height = with(density) { anchorBounds.top.toDp() - 8.dp }.coerceIn(1.dp, 280.dp)
    OverlayListPopup(show = show && anchorBounds.width > 0f, enableWindowDim = false,
        alignment = PopupPositionProvider.Align.BottomStart,
        popupPositionProvider = positionProvider,
        minWidth = 0.dp, maxHeight = height,
        onDismissRequest = onDismiss, onDismissFinished = onDismissFinished) {
        Box(Modifier.width(width).height(height)) { content() }
    }
}

@Composable
internal fun ComposerEmojiPanel(host: MiuixHost, state: MiuixHost.State, tab: String,
                                onTabSelected: (String) -> Unit, onStickerSent: () -> Unit) {
    val tabs = listOf("emoji", "kaomoji", "stickers")
    val labels = listOf(host.label("atomchat.emoji.tab.emoji"), host.label("atomchat.emoji.tab.kaomoji"),
        host.label("atomchat.emoji.tab.emote"))
    val emojiScroll = rememberLazyGridState()
    val kaomojiScroll = rememberLazyGridState()
    val stickerScroll = rememberLazyGridState()
    val kaomoji = remember(host) { host.kaomoji() }
    val selectedIndex = tabs.indexOf(tab).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = selectedIndex, pageCount = { tabs.size })
    val animations = LocalAnimationsEnabled.current
    LaunchedEffect(selectedIndex, animations) {
        if (animations) pager.springAnimateToPage(selectedIndex) else pager.scrollToPage(selectedIndex)
    }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Match the upstream "Content of Tab 1" demo: contour rail, moving indicator and pager.
        TabRowWithContour(tabs = labels, selectedTabIndex = selectedIndex,
            onTabSelected = { onTabSelected(tabs[it]) }, modifier = Modifier.testTag("composer-emoji-tabs"),
            minWidth = 0.dp)
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f),
            userScrollEnabled = false, key = { tabs[it] }) { page ->
            val category = tabs[page]
            val interactive = page == selectedIndex && !pager.isScrollInProgress
            when (category) {
                "emoji", "kaomoji" -> {
                    val values = if (category == "emoji") state.extras().emoji() else kaomoji
                    LazyVerticalGrid(columns = GridCells.Adaptive(if (category == "emoji") 44.dp else 140.dp),
                        state = if (category == "emoji") emojiScroll else kaomojiScroll,
                        modifier = Modifier.fillMaxSize().testTag("composer-$category-page"),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(values, key = { it }, contentType = { "text" }) { value ->
                            val interaction = remember { MutableInteractionSource() }
                            val hovered by interaction.collectIsHoveredAsState()
                            TextButton(value, onClick = { if (interactive) host.action(Command.APPEND, value = value) },
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (category == "emoji") Modifier.aspectRatio(1f) else Modifier.height(44.dp))
                                    .hoverable(interaction).testTag("composer-$category-cell-$value"),
                                minWidth = 0.dp, minHeight = 0.dp, cornerRadius = 10.dp,
                                insideMargin = PaddingValues(4.dp), interactionSource = interaction,
                                colors = ButtonDefaults.textButtonColors(color = emojiCellColor(hovered)),
                                textStyle = if (category == "emoji") MiuixTheme.textStyles.body1.copy(fontSize = 24.sp)
                                    else MiuixTheme.textStyles.body2)
                        }
                    }
                }
                "stickers" -> LazyVerticalGrid(columns = GridCells.Adaptive(72.dp), state = stickerScroll,
                    modifier = Modifier.fillMaxSize().testTag("composer-stickers-page"),
                    contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "add-sticker", contentType = "add") {
                        val interaction = remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        IconButton(onClick = { if (interactive) host.action(Command.ADD_STICKER) },
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).hoverable(interaction).testTag("sticker-add"),
                            cornerRadius = 10.dp, backgroundColor = emojiCellColor(hovered)) {
                            Icon(MiuixIcons.Add, host.label("atomchat.settings.action.choose"),
                                Modifier.size(28.dp).testTag(if (hovered) "sticker-add-hovered" else "sticker-add-idle"))
                        }
                    }
                    items(state.extras().stickers(), key = { "sticker:${it.key()}" }, contentType = { "sticker" }) { sticker ->
                        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(4.dp),
                            onClick = { if (interactive) { host.action(Command.SEND_STICKER, sticker.key()); onStickerSent() } },
                            onLongPress = if (sticker.server()) null else { { if (interactive) host.action(Command.DELETE_STICKER, sticker.key()) } }) {
                            StickerImage(host, sticker.key(), sticker.name(), Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(sticker.name(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                }
            }
        }
    }
}

/** Quiet square cells gain a soft surface on hover without a permanent button capsule. */
@Composable
private fun emojiCellColor(hovered: Boolean): Color {
    val animations = LocalAnimationsEnabled.current
    val target = if (hovered) MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent
    val animated by animateColorAsState(target, if (animations) tween(120) else snap(), label = "emoji-cell-hover")
    return if (animations) animated else target
}

/** Static pack thumbnails stop consulting the asynchronous host cache once loaded. */
@Composable
private fun StickerImage(host: MiuixHost, key: String, description: String, modifier: Modifier) {
    var bytes by remember(host, key) { mutableStateOf(host.image(key)) }
    LaunchedEffect(host, key) {
        var retryDelay = 100L
        while (bytes == null) {
            delay(retryDelay)
            bytes = host.image(key)
            retryDelay = (retryDelay * 2).coerceAtMost(1000L)
        }
    }
    val image = remember(key, bytes) { bytes?.let { runCatching { SkiaImage.makeFromEncoded(it) }.getOrNull() } }
    DisposableEffect(image) { onDispose { image?.close() } }
    if (image != null) Image(remember(image) { image.toComposeImageBitmap() }, description, modifier)
    else Spacer(modifier)
}
