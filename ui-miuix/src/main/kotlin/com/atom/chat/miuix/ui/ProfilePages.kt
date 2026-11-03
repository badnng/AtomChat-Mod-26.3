package com.atom.chat.miuix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import org.jetbrains.skia.Rect
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** The original profile hierarchy, hosted by upstream Miuix cards, buttons and overlays. */
@Composable
internal fun ProfilePage(host: MiuixHost, state: MiuixHost.State, padding: PaddingValues, modifier: Modifier) {
    val profile = state.profile() ?: return
    val player = profile.player() ?: return
    var editSignature by remember(player.key()) { mutableStateOf(false) }
    var editorBounds by remember { mutableStateOf(ComposeRect.Zero) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    val focusManager = LocalFocusManager.current
    val uuidLabel = host.label("atomchat.profile.uuid")
    val nameLabel = host.label("atomchat.profile.name")
    val identity = profile.facts().firstOrNull { it.label() == uuidLabel }
        ?: player.uuid()?.let { MiuixHost.Fact(uuidLabel, it, it) }
    val details = profile.facts().filter { it.label() != uuidLabel && it.label() != nameLabel }
    LazyColumn(modifier.overScrollVertical()
        .onGloballyPositioned { pageOrigin = it.boundsInRoot().topLeft }
        .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
            if (editSignature && event.changes.any { !editorBounds.contains(it.position + pageOrigin) }) {
                focusManager.clearFocus(force = true)
            }
        }, contentPadding = padding, flingBehavior = DesktopPageFlingBehavior,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(0.dp)) }
        item {
            ProfileHero(host, profile, onEditSignature = { editSignature = true },
                signatureEditor = if (!editSignature) null else ({
                    ProfileSignatureEditor(profile.signature(),
                        onBounds = { editorBounds = it },
                        onFinish = { commit, text ->
                            editSignature = false
                            if (commit) host.action(Command.SAVE_SIGNATURE, value = text)
                        })
                }))
        }
        if (profile.unsynced()) item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), insideMargin = PaddingValues(16.dp)) {
                Text(host.label("atomchat.profile.unsynced"), color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2)
            }
        }
        if (profile.statistics().isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("profile-stats"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profile.statistics().forEach { fact ->
                    Card(Modifier.weight(1f), insideMargin = PaddingValues(horizontal = 10.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val icon = when (fact.label()) {
                                host.label("atomchat.profile.ping") -> ProfileStatIcons.signal
                                host.label("atomchat.profile.session") -> ProfileStatIcons.clock
                                else -> ProfileStatIcons.stats
                            }
                            Icon(icon, null, Modifier.size(19.dp), tint = MiuixTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text(fact.value(), style = MiuixTheme.textStyles.headline1,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { contentDescription = fact.copy().ifEmpty { fact.value() } })
                                Spacer(Modifier.height(4.dp))
                                Text(fact.label(), style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (details.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), insideMargin = PaddingValues(0.dp)) {
                details.forEachIndexed { index, fact ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    // Like the original profile, role and server are information, not copy actions.
                    BasicComponent(insideMargin = PaddingValues(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(fact.label(), style = MiuixTheme.textStyles.body1)
                            Text(fact.value(), Modifier.weight(1f), textAlign = TextAlign.End,
                                style = MiuixTheme.textStyles.body2, overflow = TextOverflow.Ellipsis, maxLines = 2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
            }
        }
        if (identity != null || player.blocked()) item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), insideMargin = PaddingValues(0.dp)) {
                if (identity != null) ProfileIdentity(host, identity, state.extras())
                if (player.blocked()) {
                    if (identity != null) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    BasicComponent(title = host.label("atomchat.profile.blockstate"),
                        summary = host.label("atomchat.profile.blocked.yes"))
                }
            }
        }
        if (!profile.self()) item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), insideMargin = PaddingValues(0.dp)) {
                ArrowPreference(title = host.label("atomchat.context.whisper"),
                    modifier = Modifier.testTag("profile-whisper"),
                    onClick = { host.action(Command.PRIVATE_CHAT, player.key()) })
                SwitchPreference(checked = player.blocked(), title = host.label("atomchat.context.block"),
                    modifier = Modifier.testTag("profile-block"),
                    onCheckedChange = { host.action(Command.BLOCK, player.key()) })
                if (!state.route().readOnly()) ArrowPreference(title = host.label("atomchat.context.mention"),
                    modifier = Modifier.testTag("profile-mention"), onClick = { host.action(Command.MENTION, player.key()) })
                if (player.online()) ArrowPreference(title = host.label("atomchat.context.tp"),
                    modifier = Modifier.testTag("profile-teleport"), onClick = { host.action(Command.TELEPORT, player.key()) })
                ArrowPreference(title = host.label("atomchat.miuix.poke"),
                    modifier = Modifier.testTag("profile-poke"), onClick = { host.action(Command.POKE, player.key()) })
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ProfileHero(host: MiuixHost, profile: MiuixHost.Profile, onEditSignature: () -> Unit,
                        signatureEditor: (@Composable () -> Unit)? = null) {
    val player = profile.player()
    val colors = MiuixTheme.colorScheme
    val avatarEditable = profile.self() && LocalAvatarStyle.current != "E33CHAT"
    var avatarMenu by remember(player.key(), avatarEditable) { mutableStateOf(false) }
    var clearArmed by remember(avatarMenu) { mutableStateOf(false) }
    LaunchedEffect(clearArmed) { if (clearArmed) { delay(3_000); clearArmed = false } }
    ModalRegistration(avatarMenu)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("profile-hero"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            Column {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp),
                    onClick = if (profile.self()) ({ host.action(Command.PICK_BANNER) }) else null) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2.35f).heightIn(min = 128.dp)
                        .background(Brush.verticalGradient(listOf(colors.primary.copy(alpha = .65f), colors.surface)))
                        .onPointerEvent(PointerEventType.Press) { event ->
                        if (profile.self() && profile.customBanner() && event.buttons.isSecondaryPressed) {
                            host.action(Command.CLEAR_BANNER)
                            event.changes.forEach { it.consume() }
                        }
                    }) {
                        if (!profile.bannerKey().isNullOrEmpty()) {
                            HostImage(host, profile.bannerKey(), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else if (!profile.self()) {
                            HostImage(host, player.avatarKey(), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.surface))))
                        }
                        if (profile.self()) {
                            IconButton(onClick = { host.action(Command.PICK_BANNER) },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).testTag("profile-banner"),
                                backgroundColor = colors.surface.copy(alpha = .96f)) {
                                Text(host.label(if (profile.unsynced()) "atomchat.profile.unsynced.badge"
                                    else "atomchat.profile.banner.change"), Modifier.padding(horizontal = 12.dp),
                                    style = MiuixTheme.textStyles.footnote1)
                            }
                            if (profile.customBanner()) IconButton(onClick = { host.action(Command.CLEAR_BANNER) },
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("profile-banner-clear"),
                                backgroundColor = colors.surface.copy(alpha = .96f)) {
                                Icon(MiuixIcons.Delete, host.label("atomchat.settings.action.clear"), Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(44.dp))
            }
            // The full avatar remains inside the hero hit area while overlapping the banner edge.
            Box(Modifier.align(Alignment.BottomCenter).size(80.dp), contentAlignment = Alignment.Center) {
                if (LocalAvatarStyle.current == "E33CHAT") {
                    PlayerAvatar(host, player, 80.dp)
                } else Box(Modifier.size(80.dp).clip(CircleShape).background(colors.background).padding(4.dp),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.clip(CircleShape)) { PlayerAvatar(host, player, 72.dp) }
                }
                if (avatarEditable) {
                    IconButton(onClick = { avatarMenu = true }, holdDownState = avatarMenu,
                        modifier = Modifier.matchParentSize().testTag("profile-avatar")) {}
                    IconButton(onClick = { avatarMenu = true }, holdDownState = avatarMenu,
                        modifier = Modifier.align(Alignment.BottomEnd).size(30.dp).testTag("profile-avatar-edit"),
                        minWidth = 30.dp, minHeight = 30.dp, backgroundColor = colors.primary) {
                        Icon(MiuixIcons.Edit, host.label("atomchat.profile.avatar.change"), Modifier.size(17.dp), tint = colors.onPrimary)
                    }
                }
                OverlayListPopup(show = avatarMenu, onDismissRequest = { avatarMenu = false },
                    enableWindowDim = false, popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider) {
                    ListPopupColumn {
                        val canClear = profile.customAvatar()
                        Box(Modifier.testTag("profile-avatar-change")) {
                            DropdownImpl(item = DropdownItem(host.label("atomchat.profile.avatar.change")),
                                optionSize = if (canClear) 2 else 1, isSelected = false, index = 0,
                                onSelectedIndexChange = { host.action(Command.PICK_AVATAR); avatarMenu = false })
                        }
                        if (canClear) Box(Modifier.testTag("profile-avatar-clear")) {
                            DropdownImpl(item = DropdownItem(host.label(if (clearArmed)
                                "atomchat.profile.avatar.clear.confirm" else "atomchat.profile.avatar.clear")),
                                optionSize = 2, isSelected = false, index = 1, onSelectedIndexChange = {
                                    if (clearArmed) { host.action(Command.CLEAR_AVATAR); avatarMenu = false }
                                    else clearArmed = true
                                })
                        }
                    }
                }
            }
        }
        Text(player.displayName().orEmpty(),
            style = MiuixTheme.textStyles.title2, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        val signature = profile.signature().takeIf { it.isNotBlank() }
            ?: host.label("atomchat.profile.signature.placeholder")
        Box(Modifier.fillMaxWidth().heightIn(min = 36.dp), contentAlignment = Alignment.Center) {
            if (signatureEditor != null) signatureEditor()
            else if (profile.self()) IconButton(onClick = onEditSignature,
                modifier = Modifier.fillMaxWidth().testTag("profile-signature")) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(signature, Modifier.weight(1f, fill = false), style = MiuixTheme.textStyles.body2,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = colors.onSurfaceVariantSummary)
                    Icon(MiuixIcons.Edit, host.label("atomchat.profile.signature.label"), Modifier.size(16.dp),
                        tint = colors.onSurfaceVariantSummary)
                }
            } else Text(signature, Modifier.padding(horizontal = 8.dp), style = MiuixTheme.textStyles.body2,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = colors.onSurfaceVariantSummary)
        }
    }
}

/** Uses the same Compose platform input session as the dialog, without borrowing the chat draft. */
@Composable
private fun ProfileSignatureEditor(initial: String, onBounds: (ComposeRect) -> Unit,
                                   onFinish: (Boolean, String) -> Unit) {
    val seed = remember { initial.take(256) }
    var field by remember { mutableStateOf(TextFieldValue(seed, TextRange(seed.length))) }
    var hadFocus by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    fun finish(commit: Boolean) {
        if (!finished) {
            finished = true
            onFinish(commit, field.text)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TextField(value = field, onValueChange = { next ->
        val text = next.text.take(256)
        field = next.copy(text = text,
            selection = TextRange(next.selection.start.coerceAtMost(text.length), next.selection.end.coerceAtMost(text.length)),
            composition = next.composition?.let { TextRange(it.start.coerceAtMost(text.length), it.end.coerceAtMost(text.length)) })
    }, modifier = Modifier.fillMaxWidth().testTag("profile-signature-editor").focusRequester(focusRequester)
        .onGloballyPositioned { onBounds(it.boundsInRoot()) }
        .onFocusChanged {
            if (it.isFocused) hadFocus = true
            else if (hadFocus) finish(true)
        }.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.Escape -> { finish(false); true }
                Key.Enter, Key.NumPadEnter -> {
                    if (field.composition != null) false else { finish(true); true }
                }
                else -> false
            }
        }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish(true) }))
}

private object ProfileStatIcons {
    val signal by lazy { line("Signal", "M5 15.5V12 M10 15.5V8.5 M15 15.5V5") }
    val clock by lazy { line("Clock", "M10 3a7 7 0 1 0 0 14a7 7 0 1 0 0-14 M10 6.8V10l2.6 1.8") }
    val stats by lazy { line("Stats", "M4 15.5L8.5 11L11.5 13.5L16 7.5 M12.8 7.5H16V10.7") }
    private fun line(name: String, path: String): ImageVector =
        ImageVector.Builder("AtomChat.Profile.$name", 20.dp, 20.dp, 20f, 20f).apply {
            addPath(addPathNodes(path), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
}

@Composable
private fun ProfileIdentity(host: MiuixHost, fact: MiuixHost.Fact, extras: MiuixHost.Extras) {
    var copied by remember(fact.value()) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_200); copied = false } }
    BasicComponent(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fact.label(), style = MiuixTheme.textStyles.body1)
            IconButton(onClick = {
                host.action(Command.COPY_TEXT, value = fact.copy().ifEmpty { fact.value() })
                // The production host reports clipboard success; never replace a failed copy with a check.
                copied = host.snapshot().extras().feedbackSuccess()
            }, modifier = Modifier.size(36.dp).testTag("profile-uuid-copy"), minWidth = 36.dp, minHeight = 36.dp) {
                Icon(if (copied && extras.feedbackSuccess()) MiuixIcons.Ok else MiuixIcons.Copy,
                    host.label(if (copied) "atomchat.settings.color.copied" else "atomchat.context.copy"),
                    Modifier.size(19.dp), tint = if (copied) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Text(fact.value(), Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1,
                textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
internal fun CropDialog(host: MiuixHost, state: MiuixHost.State) {
    val crop = state.extras().cropEditor() ?: return
    val image = rememberHostImage(host, crop.sourceKey())
    var zoom by remember(crop.sourceKey()) { mutableFloatStateOf(crop.zoom()) }
    var offsetX by remember(crop.sourceKey()) { mutableFloatStateOf(crop.offsetX()) }
    var offsetY by remember(crop.sourceKey()) { mutableFloatStateOf(crop.offsetY()) }
    fun update() = host.action(Command.CROP_TRANSFORM, value = "$zoom,$offsetX,$offsetY")
    OverlayDialog(show = true, title = host.label("atomchat.picker.title"),
        onDismissRequest = { host.action(Command.CROP_CANCEL) }, defaultWindowInsetsPadding = false) {
        Column(Modifier.heightIn(max = 500.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val frameWidth = minOf(maxWidth, 180.dp * crop.aspect())
                        Canvas(Modifier.size(frameWidth, frameWidth / crop.aspect())
                            .then(if (crop.circle()) Modifier.clip(CircleShape) else Modifier)) {
                            if (image != null) {
                                val cropWidth = minOf(crop.sourceWidth().toFloat(), crop.sourceHeight() * crop.aspect()) / zoom
                                val cropHeight = cropWidth / crop.aspect()
                                val left = (crop.sourceWidth() - cropWidth) * (offsetX + 1f) / 2f
                                val top = (crop.sourceHeight() - cropHeight) * (offsetY + 1f) / 2f
                                // Crop geometry belongs to the original; the host may send a bounded preview PNG.
                                val scaleX = image.width.toFloat() / crop.sourceWidth()
                                val scaleY = image.height.toFloat() / crop.sourceHeight()
                                drawIntoCanvas { canvas -> canvas.nativeCanvas.drawImageRect(image,
                                    Rect.makeXYWH(left * scaleX, top * scaleY, cropWidth * scaleX, cropHeight * scaleY),
                                    Rect.makeWH(size.width, size.height)) }
                            }
                        }
                    }
                }
                item { SliderPreference(value = zoom, onValueChange = { zoom = it; update() },
                    title = host.label("atomchat.miuix.crop.zoom"), valueRange = 1f..8f) }
                item { SliderPreference(value = offsetX, onValueChange = { offsetX = it; update() },
                    title = host.label("atomchat.miuix.crop.horizontal"), valueRange = -1f..1f) }
                item { SliderPreference(value = offsetY, onValueChange = { offsetY = it; update() },
                    title = host.label("atomchat.miuix.crop.vertical"), valueRange = -1f..1f) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(host.label("atomchat.config.cancel"), onClick = { host.action(Command.CROP_CANCEL) }, Modifier.weight(1f))
                TextButton(host.label("atomchat.config.save"), onClick = { host.action(Command.CROP_CONFIRM) }, Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}
