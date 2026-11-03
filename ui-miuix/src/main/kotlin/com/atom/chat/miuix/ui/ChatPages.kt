package com.atom.chat.miuix.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun ConversationsPage(host: MiuixHost, state: MiuixHost.State, padding: PaddingValues, modifier: Modifier) {
    LazyColumn(modifier.overScrollVertical(), contentPadding = padding, flingBehavior = DesktopPageFlingBehavior) {
        item { Spacer(Modifier.height(12.dp)) }
        items(state.conversations(), key = { it.key() }) { conversation ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                insideMargin = PaddingValues(0.dp)) {
                BasicComponent(title = conversation.title(), summary = conversation.preview(),
                    startAction = {
                        if (conversation.player() == null) Icon(MiuixIcons.Messages, conversation.title(),
                            Modifier.size(42.dp), tint = MiuixTheme.colorScheme.primary)
                        else PlayerAvatar(host, conversation.player())
                    }, endActions = {
                        if (conversation.unread() > 0) Text(conversation.unread().toString(), color = MiuixTheme.colorScheme.primary)
                    }, modifier = Modifier.testTag("conversation-" + conversation.key()),
                    onClick = {
                        if (conversation.kind().equals("world", true)) host.action(Command.WORLD_CHAT)
                        else host.action(Command.PRIVATE_CHAT, conversation.player()?.key() ?: conversation.key())
                    })
            }
        }
        if (state.conversations().isEmpty()) item {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(host.label("atomchat.conversation.no_players"), Modifier.padding(16.dp))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun MessagesPage(host: MiuixHost, state: MiuixHost.State, padding: PaddingValues, modifier: Modifier) {
    var selectedMessage by remember { mutableStateOf<MiuixHost.Message?>(null) }
    var contextMenu by remember { mutableStateOf<MessageContextMenuState?>(null) }
    var selectedBubbleBounds by remember { mutableStateOf<Rect?>(null) }
    var shortcutsBounds by remember { mutableStateOf<Rect?>(null) }
    var outsideDismissTarget by remember { mutableStateOf<MessageContextMenuState?>(null) }
    val shortcutInput = LocalMessageShortcutInput.current
    val finishShortcuts by rememberUpdatedState {
        val open = selectedMessage != null
        selectedMessage = null
        contextMenu = null
        selectedBubbleBounds = null
        shortcutsBounds = null
        outsideDismissTarget = null
        open
    }
    val dismissShortcuts by rememberUpdatedState {
        val open = selectedMessage != null
        contextMenu?.dismiss()
        open
    }
    val backShortcuts by rememberUpdatedState { contextMenu?.back() == true }
    val outsidePress by rememberUpdatedState { position: Offset ->
        outsideDismissTarget = if (shortcutsBounds?.contains(position) != true) contextMenu else null
    }
    val outsideRelease by rememberUpdatedState {
        // Preserve the underlying action's complete click and do not dismiss a
        // newly opened menu, even when it targets the same message again.
        val target = outsideDismissTarget
        outsideDismissTarget = null
        if (target != null && contextMenu === target) dismissShortcuts()
    }
    DisposableEffect(shortcutInput) {
        val dismiss: () -> Boolean = { backShortcuts() }
        val press: (Offset) -> Unit = { outsidePress(it) }
        val release: () -> Unit = { outsideRelease() }
        shortcutInput?.dismiss = dismiss
        shortcutInput?.press = press
        shortcutInput?.release = release
        onDispose {
            if (shortcutInput != null) {
                if (shortcutInput.dismiss === dismiss) shortcutInput.dismiss = null
                if (shortcutInput.press === press) shortcutInput.press = null
                if (shortcutInput.release === release) shortcutInput.release = null
            }
        }
    }
    LaunchedEffect(state.route().page(), state.route().targetKey(), state.extras().worldFilter()) {
        dismissShortcuts()
    }
    var previewImage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val messages = state.messages()
    var highlightedId by remember { mutableStateOf<Long?>(null) }
    val jump = state.extras().jump()
    val follow = rememberSaveable { mutableStateOf(jump == null) }
    val entries = remember(state.route().page(), state.route().targetKey(), state.extras().worldFilter()) {
        MessageEntryTracker(messages, jump?.sequence())
    }
    entries.observe(messages, jump?.sequence(), state.animations() && follow.value && selectedMessage == null)
    LaunchedEffect(jump?.sequence()) {
        if (jump != null) {
            val index = messages.indexOfFirst { it.id() == jump.messageId() }
            if (index >= 0) {
                follow.value = false
                listState.scrollToItem(index + 1)
                highlightedId = jump.messageId()
                delay(1600)
                highlightedId = null
            }
        }
    }
    ModalRegistration(selectedMessage != null || previewImage != null)
    LaunchedEffect(entries, messages.lastOrNull()?.id()) {
        if (follow.value && selectedMessage == null && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex + 1)
        // scrollToItem forces the destination layout. Any unclaimed arrivals were
        // outside that viewport; reading them later must not replay an entrance.
        entries.discardPending()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> follow.value = last == null || last >= total - 2 }
    }
    // One registrar spans the currently composed message bodies, including adjacent lazy items.
    SelectionContainer {
        LazyColumn(modifier.overScrollVertical(), contentPadding = padding, state = listState) {
            item {
                DisableSelection {
                    if (state.extras().earlierMessages() > 0) TextButton(
                        text = host.text("atomchat.chat.loadEarlier", state.extras().earlierMessages()),
                        onClick = { host.action(Command.LOAD_EARLIER) }, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    else Spacer(Modifier.height(12.dp))
                }
            }
            itemsIndexed(messages, key = { _, message -> message.id() }) { index, message ->
                val enter = remember(entries, message.id()) { entries.take(message.id()) }
                val progress = remember(entries, message.id()) { Animatable(if (enter) 0f else 1f) }
                val lift = with(LocalDensity.current) { 16.dp.toPx() }
                LaunchedEffect(entries, message.id(), state.animations(), message.animateEntry()) {
                    if (enter && state.animations() && message.animateEntry()) {
                        // Lazy prefetch can compose an item before follow's scrollToItem. Wait
                        // for that layout, then animate only if it actually reached the viewport.
                        withFrameNanos { }
                        if (listState.layoutInfo.visibleItemsInfo.any { it.key == message.id() }) {
                            progress.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
                        } else progress.snapTo(1f)
                    } else progress.snapTo(1f)
                }
                MessageCard(host, message, state.appearance(), highlighted = highlightedId == message.id(),
                    groupedAfter = messages.getOrNull(index + 1)?.grouped() == true,
                    modifier = if (enter) Modifier.graphicsLayer {
                        val fraction = if (state.animations() && message.animateEntry()) progress.value else 1f
                        alpha = fraction
                        translationY = lift * (1f - fraction)
                    } else Modifier,
                    onMenu = { bounds ->
                        if (selectedMessage?.id() == message.id() && contextMenu?.isOpen == true) dismissShortcuts()
                        else {
                            selectedMessage = message
                            contextMenu = MessageContextMenuState()
                            selectedBubbleBounds = bounds
                            shortcutsBounds = null
                        }
                    },
                    onBubbleBounds = { if (selectedMessage?.id() == message.id()) selectedBubbleBounds = it },
                    onImage = { dismissShortcuts(); previewImage = it })
            }
            if (messages.isEmpty()) item {
                DisableSelection {
                    Text(host.label("atomchat.conversation.empty"), modifier = Modifier.padding(24.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (!follow.value) Box(Modifier.fillMaxSize().padding(padding).padding(12.dp), contentAlignment = Alignment.BottomEnd) {
        IconButton(onClick = {
            follow.value = true
            host.action(Command.JUMP_LATEST)
            scope.launch { listState.scrollToItem((messages.size + 1).coerceAtLeast(0)) }
        }, modifier = Modifier.testTag("chat-jump-latest"), backgroundColor = MiuixTheme.colorScheme.secondaryContainer) {
            Icon(MiuixIcons.ExpandMore, host.label("atomchat.miuix.jump_latest"))
        }
    }
    val selected = selectedMessage
    val menu = contextMenu
    if (selected != null && menu != null) MessageMenuOverlay(host, state, selected,
        anchor = selectedBubbleBounds, padding = padding, menu = menu,
        onBounds = { shortcutsBounds = it },
        onDismiss = { if (contextMenu === menu) finishShortcuts() })
    previewImage?.let { imageKey ->
        OverlayDialog(show = true, onDismissRequest = { previewImage = null }, defaultWindowInsetsPadding = false) {
            Column {
                HostImage(host, imageKey, Modifier.fillMaxWidth().heightIn(max = 320.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(host.label("atomchat.config.cancel"), { previewImage = null }, Modifier.weight(1f))
                    TextButton(host.label("atomchat.context.save"), { host.action(Command.SAVE_IMAGE, imageKey) }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Arrival is a snapshot change, not the host's persistent animateEntry eligibility flag. */
private class MessageEntryTracker(messages: List<MiuixHost.Message>, jumpSequence: Long?) {
    private var previousIds = messages.map { it.id() }
    private var previousJump = jumpSequence
    private val pending = mutableSetOf<Long>()

    fun observe(messages: List<MiuixHost.Message>, jumpSequence: Long?, enabled: Boolean) {
        val ids = messages.map { it.id() }
        if (!enabled || jumpSequence != previousJump) pending.clear()
        if (ids != previousIds) {
            val tail = previousIds.lastOrNull()
            val tailIndex = if (tail == null) -1 else ids.indexOf(tail)
            // Loading older rows prepends IDs. Replaced transcripts establish a new
            // baseline, so filtering, history navigation and retention never replay them.
            if (enabled && jumpSequence == previousJump && (tail == null || tailIndex >= 0)) {
                val known = previousIds.toHashSet()
                messages.drop(tailIndex + 1).filter { it.id() !in known && it.animateEntry() }
                    .forEach { pending.add(it.id()) }
            }
            pending.retainAll(ids.toSet())
            previousIds = ids
        }
        previousJump = jumpSequence
    }

    fun take(id: Long): Boolean = pending.remove(id)

    fun discardPending() = pending.clear()
}

@Composable
private fun MessageCard(host: MiuixHost, message: MiuixHost.Message, appearance: MiuixHost.Appearance, highlighted: Boolean,
                        groupedAfter: Boolean, modifier: Modifier, onMenu: (Rect?) -> Unit,
                        onBubbleBounds: (Rect) -> Unit, onImage: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val menuLabel = host.label("atomchat.miuix.message_actions")
    var rowCoordinates by remember(message.id()) { mutableStateOf<LayoutCoordinates?>(null) }
    var bubbleBounds by remember(message.id()) { mutableStateOf<Rect?>(null) }
    var pointerInRow by remember(message.id()) { mutableStateOf<Offset?>(null) }
    val menuModifier = Modifier.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        if (event.buttons.isSecondaryPressed) {
            onMenu(bubbleBounds)
            event.changes.forEach { it.consume() }
        }
    }.onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { event ->
        pointerInRow = event.changes.firstOrNull()?.position
    }.onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { event ->
        pointerInRow = event.changes.firstOrNull()?.position
    }.onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
        pointerInRow = null
    }.semantics { customActions = listOf(CustomAccessibilityAction(menuLabel) { onMenu(bubbleBounds); true }) }
    val metrics = host.uiMetrics()
    val palette = host.uiPalette()
    // Host metrics already include UiTokens.SCALE. Derive only unexposed tokens.
    val unit = metrics.avatarSize() / 40f
    val inset = maxOf(1.5f * unit, if (appearance.avatarStyle() == "E33CHAT") metrics.avatarSize() * .05f else 0f).dp
    val avatarColumn = metrics.avatarSize().dp + metrics.avatarGap().dp + inset
    val bodyStyle = MiuixTheme.textStyles.body1.copy(
        fontSize = (if (message.system()) metrics.quoteFont() else metrics.bodyFont()).sp,
        lineHeight = TextUnit.Unspecified)
    val bodyColor = Color(if (message.system()) palette.capsuleText()
        else if (message.own()) appearance.ownText() else appearance.otherText())
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val annotated = remember(host, message.bodyRuns(), message.content(), message.id()) {
        runs(host, message.bodyRuns(), message.content(), message.id())
    }
    val rowGap = if (groupedAfter) maxOf(2f * unit, 10f * unit / 3f) else 10f * unit
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = metrics.panelInset().dp)
        .padding(bottom = rowGap.dp).testTag("message-" + message.id())
        .onGloballyPositioned { rowCoordinates = it }.then(menuModifier)) {
        val copyOutset = 26.dp
        val bubbleMax = (if (message.system()) maxWidth - copyOutset * 2
            else maxWidth - (106f * unit).dp).coerceAtLeast(1.dp)
        val padX = (12f * unit).dp.coerceAtMost(bubbleMax / 2)
        val textMaxPx = with(density) { (bubbleMax - padX * 2).roundToPx().coerceAtLeast(1) }
        val measured = textMeasurer.measure(annotated, style = bodyStyle,
            constraints = Constraints(maxWidth = textMaxPx))
        val longestLine = (0 until measured.lineCount).maxOfOrNull {
            measured.getLineRight(it) - measured.getLineLeft(it)
        } ?: 0f
        val textWidth = with(density) { ceil(longestLine).toDp() }
        val imageWidth = if (message.imageKey().isNullOrEmpty()) 0.dp else (220f * unit).dp
        val bubbleWidth = maxOf(textWidth + padX * 2, imageWidth,
            ((if (message.system()) 40f else 24f) * unit).dp).coerceAtMost(bubbleMax)
        var copySize by remember(message.id()) { mutableStateOf(IntSize.Zero) }
        var copyAnchor by remember(message.id(), annotated) { mutableStateOf<Offset?>(null) }
        var bubbleCoordinates by remember(message.id()) { mutableStateOf<LayoutCoordinates?>(null) }
        var textCoordinates by remember(message.id()) { mutableStateOf<LayoutCoordinates?>(null) }
        var textLayout by remember(message.id(), annotated) { mutableStateOf<TextLayoutResult?>(null) }
        fun updateCopyAnchor() {
            val bubble = bubbleCoordinates?.takeIf { it.isAttached } ?: return
            val text = textCoordinates?.takeIf { it.isAttached } ?: return
            val end = textLayout?.let(::lastVisibleGlyphCorner) ?: return
            copyAnchor = bubble.localPositionOf(text, end)
        }
        val edge = if (message.system()) Alignment.TopCenter else if (message.own()) Alignment.TopEnd else Alignment.TopStart
        if (!message.system() && !message.grouped()) DisableSelection {
            Box(Modifier.align(if (message.own()) Alignment.TopEnd else Alignment.TopStart)
                .padding(start = if (message.own()) 0.dp else inset, end = if (message.own()) inset else 0.dp,
                    top = (4f * unit).dp)) {
                Box(Modifier.size(metrics.avatarSize().dp).testTag("message-avatar-" + message.id())
                    .clickable(role = Role.Button) { host.action(Command.AVATAR_CLICK, message.id().toString()) }) {
                    PlayerAvatar(host, message.sender(), metrics.avatarSize().dp, metrics.avatarSize().dp / 2)
                }
            }
        }
        Column(Modifier.align(edge).padding(
            start = if (!message.system() && !message.own()) avatarColumn else 0.dp,
            end = if (!message.system() && message.own()) avatarColumn else 0.dp),
            horizontalAlignment = if (message.system()) Alignment.CenterHorizontally
                else if (message.own()) Alignment.End else Alignment.Start) {
            DisableSelection {
                if (!message.system() && !message.grouped()) Box(
                    Modifier.height((26f * unit).dp).widthIn(max = bubbleMax),
                    contentAlignment = if (message.own()) Alignment.CenterEnd else Alignment.CenterStart) {
                    // Match the original name band: retain server colors and weights,
                    // but leave click/hover and underline decoration in message bodies.
                    val senderName = remember(host, message.senderRuns(), message.sender()?.displayName()) {
                        runs(host, message.senderRuns().map {
                            MiuixHost.Run(it.text(), it.argb(), it.bold(), it.italic(), false,
                                it.strikethrough(), it.obfuscated(), "")
                        }, message.sender()?.displayName() ?: "", message.id())
                    }
                    Text(senderName, color = Color(palette.primaryText()),
                        style = bodyStyle.copy(fontSize = metrics.nameFont().sp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                if (!message.quoteText().isNullOrEmpty()) Column(
                    Modifier.widthIn(max = bubbleMax).padding(bottom = (4f * unit).dp)
                        .background(Color(palette.capsuleBackground()), RoundedCornerShape((10f * unit).dp))
                        .padding(horizontal = (8f * unit).dp, vertical = (3f * unit).dp)) {
                    Text(runs(host, message.quoteNameRuns(), message.quoteName(), message.id()),
                        color = Color(palette.capsuleText()), style = bodyStyle.copy(fontSize = metrics.quoteFont().sp))
                    Text(runs(host, message.quoteTextRuns(), message.quoteText(), message.id()), maxLines = 3,
                        overflow = TextOverflow.Ellipsis, color = Color(palette.capsuleText()),
                        style = bodyStyle.copy(fontSize = metrics.quoteFont().sp))
                }
            }
            // Extra width is reserved on the glyph's right, with an offset that
            // keeps the filled bubble aligned to its original message edge.
            val copyOffset = if (message.system()) copyOutset / 2 else if (message.own()) copyOutset else 0.dp
            Box(Modifier.width(bubbleWidth + copyOutset).offset(x = copyOffset).onSizeChanged { copySize = it }
                .onGloballyPositioned {
                    bubbleCoordinates = it
                    updateCopyAnchor()
                }) {
                // The copy affordance occupies the former action-row space, outside
                // the bubble fill, so revealing it never moves the message text.
                Column(Modifier.width(bubbleWidth).padding(bottom = copyOutset)) {
                    Column(Modifier.fillMaxWidth()
                        .padding(top = if (message.system()) (2f * unit).dp else 0.dp)
                        .onGloballyPositioned {
                            bubbleBounds = it.boundsInRoot()
                            onBubbleBounds(it.boundsInRoot())
                        }
                        .background(if (highlighted) colors.tertiaryContainer else Color(
                            if (message.system()) palette.capsuleBackground()
                            else if (message.own()) appearance.ownBubble() else appearance.otherBubble()),
                            RoundedCornerShape(((if (message.system()) 10f else 12f) * unit).dp))
                        .padding(horizontal = padX, vertical = ((if (message.system()) 3f else 5.5f) * unit).dp)) {
                        if (message.content().isNotEmpty()) MessageText(host, message, bodyStyle, bodyColor,
                            onLayout = { textLayout = it; updateCopyAnchor() },
                            onCoordinates = { textCoordinates = it; updateCopyAnchor() })
                        DisableSelection {
                            if (!message.imageKey().isNullOrEmpty()) IconButton(
                                onClick = { onImage(message.imageKey()) }, modifier = Modifier.fillMaxWidth()) {
                                val image = rememberHostImage(host, message.imageKey())
                                if (image == null) Text(host.label("atomchat.image.loading"), color = bodyColor)
                                else HostImage(host, message.imageKey(), Modifier.fillMaxWidth().heightIn(max = (140f * unit).dp))
                            }
                        }
                    }
                }
                DisableSelection {
                    val fallback = Offset(with(density) { (bubbleWidth - padX).toPx() },
                        copySize.height - with(density) { (26.dp + ((if (message.system()) 3f else 5.5f) * unit).dp).toPx() })
                    val pointer = pointerInRow?.let { point ->
                        val row = rowCoordinates?.takeIf { it.isAttached }
                        val bubble = bubbleCoordinates?.takeIf { it.isAttached }
                        if (row != null && bubble != null) bubble.localPositionOf(row, point) else null
                    }
                    MessageCopyButton(host, message.id().toString(), copyAnchor ?: fallback, pointer, copySize,
                        Modifier.testTag("message-copy-" + message.id()))
                }
            }
            DisableSelection {
                // Metadata and actions must not widen a short body bubble.
                Row(Modifier.widthIn(max = bubbleMax), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((4f * unit).dp)) {
                    if (!message.timestampLabel().isNullOrEmpty()) Text(message.timestampLabel(),
                        color = Color(palette.secondaryText()), style = bodyStyle.copy(fontSize = metrics.timeFont().sp))
                    if (message.duplicates() > 1) Text("×${message.duplicates()}", color = Color(palette.secondaryText()),
                        style = bodyStyle.copy(fontSize = metrics.timeFont().sp))
                }
                if (message.reactions().isNotEmpty()) FlowRow(Modifier.widthIn(max = bubbleMax),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.reactions().forEach { reaction ->
                        TextButton("${reaction.emoji()} ${reaction.count()}",
                            onClick = { host.action(Command.REACT, message.id().toString(), reaction.emoji()) },
                            colors = if (reaction.own()) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                    }
                }
            }
        }
    }
}

/** Keep text selection and the host's rich click/hover events on the same text node. */
@Composable
private fun MessageText(host: MiuixHost, message: MiuixHost.Message, style: TextStyle, color: Color,
                        onLayout: (TextLayoutResult) -> Unit, onCoordinates: (LayoutCoordinates) -> Unit) {
    var layout by remember(message.id()) { mutableStateOf<TextLayoutResult?>(null) }
    var hover by remember(message.id()) { mutableStateOf("") }
    val tooltip = rememberTooltipState()
    LaunchedEffect(hover) {
        tooltip.dismiss()
        if (hover.isNotEmpty()) { delay(400); tooltip.show() }
    }
    val annotated = remember(host, message.bodyRuns(), message.content(), message.id()) {
        runs(host, message.bodyRuns(), message.content(), message.id())
    }
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        state = tooltip, enableUserInput = false,
        tooltip = {
            PlainTooltip {
                Text(hover, style = MiuixTheme.textStyles.body2)
            }
        }) {
        Text(annotated, style = style, color = color,
            onTextLayout = { layout = it; onLayout(it) }, modifier = Modifier.onGloballyPositioned(onCoordinates)
                .onPointerEvent(PointerEventType.Move) { event ->
                    val position = event.changes.firstOrNull()?.position
                    val offset = position?.let { layout?.getOffsetForPosition(it) }
                    var start = 0
                    val run = if (offset == null) null else message.bodyRuns().firstOrNull {
                        val end = start + it.text().length
                        val match = offset >= start && offset < end
                        start = end
                        match
                    }
                    hover = run?.interaction()?.takeIf { it.isNotEmpty() }?.let(host::hoverText) ?: ""
                }.onPointerEvent(PointerEventType.Exit) { hover = "" })
    }
}

/** Locate the final visible glyph without splitting a surrogate pair. */
private fun lastVisibleGlyphCorner(layout: TextLayoutResult): Offset? {
    val text = layout.layoutInput.text.text
    for (line in layout.lineCount - 1 downTo 0) {
        val start = layout.getLineStart(line)
        var end = layout.getLineEnd(line, visibleEnd = true).coerceIn(start, text.length)
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end > start) {
            var offset = end - Character.charCount(Character.codePointBefore(text, end))
            var glyph = layout.getBoundingBox(offset)
            // Some cluster continuations (variation selectors, ZWJ, combining
            // marks) have no separate box. Walk back to their visible base.
            while (glyph.width <= 0f && offset > start) {
                offset -= Character.charCount(Character.codePointBefore(text, offset))
                glyph = layout.getBoundingBox(offset)
            }
            if (glyph.width > 0f && glyph.height > 0f) return glyph.bottomRight
            val caret = layout.getCursorRect(end)
            return Offset(caret.right, caret.bottom)
        }
    }
    return null
}

internal fun runs(host: MiuixHost, runs: List<MiuixHost.Run>, fallback: String, messageId: Long): AnnotatedString = buildAnnotatedString {
    if (runs.isEmpty()) append(fallback)
    else runs.forEach { run ->
        val decoration = buildList {
            if (run.underlined()) add(TextDecoration.Underline)
            if (run.strikethrough()) add(TextDecoration.LineThrough)
        }
        pushStyle(SpanStyle(color = run.argb()?.let { Color(it) } ?: Color.Unspecified,
            fontWeight = if (run.bold()) FontWeight.Bold else null,
            fontStyle = if (run.italic()) FontStyle.Italic else null,
            textDecoration = if (decoration.isEmpty()) null else TextDecoration.combine(decoration)))
        if (!run.interaction().isNullOrEmpty()) pushLink(LinkAnnotation.Clickable(
            tag = run.interaction(),
            styles = run.argb()?.let { TextLinkStyles(style = SpanStyle(color = Color(it))) },
            linkInteractionListener = { host.action(Command.INTERACT, messageId.toString(), run.interaction()) }))
        append(if (run.obfuscated()) run.text().map { if (it.isWhitespace()) it else '▒' }.joinToString("") else run.text())
        if (!run.interaction().isNullOrEmpty()) pop()
        pop()
    }
}

@Composable
internal fun ComposerBar(host: MiuixHost, state: MiuixHost.State, backdrop: LayerBackdrop, blur: Boolean) {
    val composer = state.composer()
    val writable = composer.enabled() && !state.route().readOnly()
    var extrasTab by remember { mutableStateOf<String?>(null) }
    var retainedExtrasTab by remember { mutableStateOf<String?>(null) }
    var field by remember { mutableStateOf(TextFieldValue(composer.text(), TextRange(composer.anchor(), composer.cursor())) ) }
    var focus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var restoreFocusAfterPopup by remember { mutableStateOf(false) }
    SideEffect { if (extrasTab != null) retainedExtrasTab = extrasTab }
    fun toggleExtras(tab: String) {
        val emojiGroupOpen = extrasTab == "emoji" || extrasTab == "kaomoji" || extrasTab == "stickers"
        if (extrasTab == tab || (tab == "emoji" && emojiGroupOpen)) {
            restoreFocusAfterPopup = true
            extrasTab = null
        } else {
            restoreFocusAfterPopup = false
            extrasTab = tab
        }
    }
    var focusRestoreSequence by remember { mutableIntStateOf(0) }
    var pendingPhraseEditor by remember { mutableStateOf<PhraseEditorRequest?>(null) }
    var activePhraseEditor by remember { mutableStateOf<PhraseEditorRequest?>(null) }
    var showPhraseEditor by remember { mutableStateOf(false) }
    var composerPopupBounds by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(activePhraseEditor) {
        if (activePhraseEditor != null) {
            // Unregister the list popup from the root host before registering the editor dialog.
            withFrameNanos { }
            withFrameNanos { }
            showPhraseEditor = true
        }
    }
    LaunchedEffect(focusRestoreSequence) {
        if (focusRestoreSequence > 0 && writable) {
            // Restore only after the popup has exited and its editor input session has been disposed.
            withFrameNanos { }
            withFrameNanos { }
            if (extrasTab == null && activePhraseEditor == null) focusRequester.requestFocus()
        }
    }
    val suggestions = state.extras().suggestions()
    var suggestionIndex by remember(field.text, suggestions) { mutableIntStateOf(0) }
    var dismissedQuery by remember { mutableStateOf<String?>(null) }
    val showSuggestions = focus && suggestions.isNotEmpty() && dismissedQuery != field.text
    val shortcutInput = LocalMessageShortcutInput.current
    val popupActive by rememberUpdatedState {
        showSuggestions || (extrasTab != null && writable) || activePhraseEditor != null || showPhraseEditor
    }
    DisposableEffect(shortcutInput) {
        val active: () -> Boolean = { popupActive() }
        shortcutInput?.popupActive = active
        onDispose {
            if (shortcutInput != null && shortcutInput.popupActive === active) shortcutInput.popupActive = null
        }
    }
    LaunchedEffect(state.route().page(), state.route().targetKey(), composer.enabled()) {
        if (composer.enabled() && !state.route().readOnly()) focusRequester.requestFocus()
    }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    LaunchedEffect(composer.text(), composer.cursor(), composer.anchor()) {
        val range = TextRange(composer.anchor().coerceIn(0, composer.text().length), composer.cursor().coerceIn(0, composer.text().length))
        if (field.text != composer.text() || field.selection != range) field = TextFieldValue(composer.text(), range)
    }
    DisposableEffect(Unit) { onDispose {
        host.composerFocusChanged(false)
        host.requestTextInput(false, 0f, 0f, 0f, 0f)
    } }
    Column(Modifier.fillMaxWidth().progressiveChatGlass(backdrop, bottom = true, enabled = blur,
        opacity = state.appearance().opacity()).padding(12.dp)
        .composerPopupAnchor { composerPopupBounds = it }) {
        if (composer.replyId() != null) Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), insideMargin = PaddingValues(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(host.text("atomchat.reply.to", composer.replyName(), composer.replyText()),
                    Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { host.action(Command.CANCEL_REPLY) }) { Icon(MiuixIcons.Close, host.label("atomchat.config.cancel")) }
            }
        }
        if (composer.partnerTyping()) Text(host.label("atomchat.private.typing"), style = MiuixTheme.textStyles.footnote1)
        if (state.route().readOnly()) Text(state.route().readOnlyHint(), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
            TextField(value = field, onValueChange = {
                val text = it.text.take(256)
                field = it.copy(text = text, selection = TextRange(it.selection.start.coerceAtMost(text.length),
                    it.selection.end.coerceAtMost(text.length)))
                host.setText(field.text, field.selection.end, field.selection.start)
            }, modifier = Modifier.fillMaxWidth().testTag("chat-composer").focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false
                    else when {
                        showSuggestions && event.key == Key.DirectionDown -> {
                            suggestionIndex = (suggestionIndex + 1) % suggestions.size; true
                        }
                        showSuggestions && event.key == Key.DirectionUp -> {
                            suggestionIndex = (suggestionIndex - 1 + suggestions.size) % suggestions.size; true
                        }
                        showSuggestions && event.key == Key.Tab -> {
                            host.action(Command.COMPLETE, suggestionIndex.toString()); true
                        }
                        event.key == Key.Enter && !event.isShiftPressed -> {
                            if (showSuggestions) host.action(Command.COMPLETE, suggestionIndex.toString())
                            else host.action(Command.SEND)
                            true
                        }
                        else -> false
                    }
                }.onGloballyPositioned {
                bounds = it.boundsInRoot()
                if (focus) host.requestTextInput(true, bounds.left, bounds.top, bounds.width, bounds.height)
            }.onFocusChanged {
                focus = it.isFocused
                host.composerFocusChanged(focus)
                host.requestTextInput(focus, bounds.left, bounds.top, bounds.width, bounds.height)
            }, label = host.label("atomchat.input.placeholder"), useLabelAsPlaceholder = true,
                enabled = writable, maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { host.action(Command.SEND) }))
            OverlayListPopup(show = showSuggestions, enableWindowDim = false,
                alignment = PopupPositionProvider.Align.BottomStart,
                popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                onDismissRequest = { dismissedQuery = field.text }, maxHeight = 200.dp) {
                ListPopupColumn {
                    suggestions.forEachIndexed { index, suggestion ->
                        DropdownImpl(item = DropdownItem(suggestion.text(), summary = suggestion.tooltip()),
                            optionSize = suggestions.size, isSelected = index == suggestionIndex, index = index,
                            onSelectedIndexChange = { host.action(Command.COMPLETE, index.toString()) })
                    }
                }
            }
            }
            Button(onClick = { host.action(Command.SEND) }, enabled = writable && field.text.isNotBlank(),
                colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.testTag("chat-send")) {
                Icon(MiuixIcons.Send, host.label("atomchat.miuix.send"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { toggleExtras("emoji") }, enabled = writable,
                modifier = Modifier.testTag("chat-emoji")) {
                Icon(AtomChatIcons.Emoji, host.label("atomchat.emoji.tab.emoji"))
            }
            IconButton(onClick = { toggleExtras("phrases") }, enabled = writable,
                modifier = Modifier.testTag("chat-phrases")) {
                Icon(AtomChatIcons.QuickPhrase, host.label("atomchat.quick.add"))
            }
            IconButton(onClick = { host.action(Command.PICK_IMAGE) }, enabled = writable,
                modifier = Modifier.testTag("chat-image")) {
                Icon(MiuixIcons.Image, host.label("atomchat.picker.title"))
            }
        }
        ComposerExtrasPopup(show = extrasTab != null && writable, anchorBounds = composerPopupBounds,
            onDismiss = { restoreFocusAfterPopup = true; extrasTab = null },
            onDismissFinished = finished@{
                if (extrasTab != null) return@finished
                retainedExtrasTab = null
                val editor = pendingPhraseEditor
                if (editor != null) {
                    pendingPhraseEditor = null
                    activePhraseEditor = editor
                } else if (restoreFocusAfterPopup) {
                    restoreFocusAfterPopup = false
                    focusRestoreSequence++
                }
            }) {
            (extrasTab ?: retainedExtrasTab)?.let {
                if (it != "phrases") {
                    ComposerEmojiPanel(host, state, it, onTabSelected = { tab -> extrasTab = tab },
                        onStickerSent = { restoreFocusAfterPopup = true; extrasTab = null })
                } else {
                    ComposerExtras(host, state, it,
                        onInserted = {
                            restoreFocusAfterPopup = true
                            extrasTab = null
                        }, onEditPhrase = { phrase ->
                            restoreFocusAfterPopup = false
                            pendingPhraseEditor = PhraseEditorRequest(phrase?.index() ?: -1, phrase?.text() ?: "")
                            extrasTab = null
                        })
                }
            }
        }
    }
    activePhraseEditor?.let { editor ->
        EditDialog(showPhraseEditor, host.label("atomchat.quick.add"), editor.text,
            host.label("atomchat.config.cancel"), host.label("atomchat.config.save"),
            onDismiss = { showPhraseEditor = false },
            onConfirm = { host.action(Command.SAVE_PHRASE, editor.index.toString(), it) },
            onDismissFinished = {
                activePhraseEditor = null
                focusRestoreSequence++
            })
    }
}

private data class PhraseEditorRequest(val index: Int, val text: String)

@Composable
private fun ComposerExtras(host: MiuixHost, state: MiuixHost.State, tab: String, onInserted: () -> Unit,
                           onEditPhrase: (MiuixHost.Phrase?) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(8.dp).overScrollVertical()) {
        if (tab == "phrases") {
            item { TextButton(host.label("atomchat.quick.add"), { onEditPhrase(null) }, modifier = Modifier.testTag("phrase-add")) }
            items(state.extras().phrases(), key = { "${it.server()}:${it.index()}" }) { phrase ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), insideMargin = PaddingValues(0.dp),
                    onClick = { host.action(Command.INSERT_PHRASE, phrase.index().toString(), if (phrase.server()) "server" else ""); onInserted() },
                    onLongPress = if (phrase.server()) null else { { onEditPhrase(phrase) } }) {
                    BasicComponent(title = phrase.text(), endActions = {
                        if (!phrase.server()) IconButton(onClick = { host.action(Command.DELETE_PHRASE, phrase.index().toString()) }) {
                            Icon(MiuixIcons.Delete, host.label("atomchat.config.phrase_delete"))
                        }
                    })
                }
            }
        }
    }

}
