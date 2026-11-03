package com.atom.chat.miuix.ui

import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import org.jetbrains.skia.Image
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.*
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

internal fun MiuixHost.action(command: Command, target: String = "", value: String = "", commit: Boolean = true) =
    perform(MiuixHost.Action(command, target, value, commit))
internal fun MiuixHost.label(key: String) = text(key)

private val AppBarStatesSaver = listSaver<MutableMap<PageIdentity, TopAppBarState>, Any>(
    save = { states -> states.flatMap { (page, bar) ->
        listOf(page.page, page.target, page.section, bar.heightOffsetLimit, bar.heightOffset, bar.contentOffset)
    } },
    restore = { values -> values.chunked(6).associate { row ->
        PageIdentity(row[0] as String, row[1] as String, row[2] as String) to
            TopAppBarState(row[3] as Float, row[4] as Float, row[5] as Float)
    }.toMutableMap() },
)

/** The optional style owns a real Compose tree; AtomChat's domain state remains in the host. */
@Composable
fun MiuixApp(host: MiuixHost, state: MiuixHost.State, worldImage: Image?,
             worldBackdropRequired: Boolean = host.worldBackdropRequired(state)) {
    val dark = Color(state.appearance().panel()).luminance() < .5f
    val palette = remember(dark, state.appearance().accent()) {
        if (dark) darkColorScheme(primary = Color(state.appearance().accent()))
        else lightColorScheme(primary = Color(state.appearance().accent()))
    }
    val typography = remember { miuixTextStyles() }
    MiuixTheme(colors = palette, textStyles = typography) {
        val modalCount = remember { mutableIntStateOf(0) }
        val messageShortcutInput = remember { MessageShortcutInput() }
        CompositionLocalProvider(LocalModalCount provides modalCount,
            LocalMessageShortcutInput provides messageShortcutInput,
            LocalAvatarStyle provides state.appearance().avatarStyle(),
            LocalAnimationsEnabled provides state.animations()) {
            val worldBackdrop = rememberLayerBackdrop()
            val blur = state.appearance().blur() && isRuntimeShaderSupported()
            val wallpaper = rememberHostImage(host, state.appearance().wallpaperKey())
            val radius = state.appearance().cornerRadius().coerceAtLeast(0f).dp
            Box(Modifier.fillMaxSize().squircleClip(radius).testTag(
                if (typography.main.fontFamily != null) "miuix-font-noto-variable" else "miuix-font-platform")) {
                if (blur && wallpaper == null && worldBackdropRequired) {
                    WorldSource(worldImage, worldBackdrop, Modifier.testTag("miuix-world-source"))
                }
                PageNavigation(host, state) { pageHost, pageState ->
                    AppScaffold(pageHost, pageState, worldBackdrop, blur, wallpaper)
                }
            }
        }
    }
}

@Composable
private fun AppScaffold(host: MiuixHost, state: MiuixHost.State, worldBackdrop: LayerBackdrop,
                        blur: Boolean, wallpaper: Image?) {
    // Each exit layer records its own page, never the incoming page's backdrop.
    val backdrop = rememberLayerBackdrop()
    val route = state.route()
    val focus = LocalFocusManager.current
    val chat = route.page() == "world_chat" || route.page() == "private_chat"
    val about = state.hasOpaquePageBackground()
    val aboutListState = rememberLazyListState()
    val aboutProgress by remember(aboutListState) { derivedStateOf { aboutScrollProgress(aboutListState) } }
    // Root tabs share their shell, while each tab keeps its own collapse position.
    val barStates = rememberSaveable(saver = AppBarStatesSaver) { mutableMapOf() }
    val barState = barStates.getOrPut(state.pageIdentity()) { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
    val scrollBehavior = MiuixScrollBehavior(state = barState)
    val collapsed by remember(barState, chat, about, aboutListState) {
        derivedStateOf { chat || if (about) aboutScrollProgress(aboutListState) >= 1f else barState.collapsedFraction >= .99f }
    }
    var noticePadding by remember { mutableStateOf<PaddingValues?>(null) }
    val messageShortcutInput = LocalMessageShortcutInput.current
    var scaffoldCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Scaffold(
        modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { scaffoldCoordinates = it }
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.Escape &&
                    messageShortcutInput?.popupActive?.invoke() != true &&
                    messageShortcutInput?.dismiss?.invoke() == true
            }
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
                val coordinates = scaffoldCoordinates
                val position = event.changes.firstOrNull()?.position
                if (coordinates?.isAttached == true && position != null) {
                    messageShortcutInput?.press?.invoke(coordinates.localToRoot(position))
                }
            }
            .onPointerEvent(PointerEventType.Release, PointerEventPass.Final) {
                messageShortcutInput?.release?.invoke()
            },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopChatGlass(backdrop, enabled = blur, compact = chat, surfaceVisible = !about || aboutProgress >= 1f,
                collapseProgress = { if (about) aboutProgress else barState.collapsedFraction }) {
                Box(Modifier.testTag(if (collapsed) "miuix-topbar-collapsed" else "miuix-topbar-expanded")) {
                    ChatTopAppBar(title = route.title(), scrollBehavior = scrollBehavior,
                        compact = chat || about,
                        titleAlpha = { 1f },
                        modifier = Modifier.testTag("miuix-top-app-bar"),
                        navigationIcon = {
                            if (route.canGoBack()) IconButton(onClick = {
                                focus.clearFocus(force = true)
                                host.action(Command.BACK)
                            },
                                modifier = Modifier.testTag("navigate-back")) {
                                Icon(MiuixIcons.Back, host.label("gui.back"))
                            }
                        }, actions = {
                            if (chat && route.page() == "world_chat") ChatFilterButton(host, state)
                        })
                }
            }
        },
        bottomBar = {
            if (chat) ComposerBar(host, state, backdrop, blur)
            else if (!route.canGoBack()) BottomNavigation(host, state, backdrop, blur)
        },
        popupHost = {
            // The top bar feathers beyond its layout bounds. Notices belong above
            // that drawing, while upstream menus and dialogs retain their priority.
            noticePadding?.let { NoticeOverlay(host, state, it) }
            MiuixPopupHost()
        },
    ) { padding ->
        SideEffect { noticePadding = padding }
        // Record the page background and scrolling content together. Bar consumers are siblings
        // of this source and can sample the tinted world through gaps between cards.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (about) {
                // AboutBackground owns the opaque surface and OS3 artwork. Sampling
                // and blurring a hidden world/wallpaper underneath does no visible work.
            } else if (wallpaper != null) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
                ComposeImage(remember(wallpaper) { wallpaper.toComposeImageBitmap() },
                    contentDescription = null, modifier = Modifier.fillMaxSize().alpha(state.appearance().opacity().coerceIn(0f, 1f)),
                    contentScale = ContentScale.Crop)
            } else Box(Modifier.fillMaxSize().testTag("miuix-world-page-glass")
                .mixedChatGlass(worldBackdrop, blur, state.appearance().opacity()))
            // A transcript follows new messages programmatically. Its scroll position must
            // never collapse the header or shift the text the player is reading.
            val pageModifier = if (chat || about) Modifier.fillMaxSize()
                else Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)
            CompositionLocalProvider(LocalAboutListState provides aboutListState) {
                if (state.isRootTab()) RootTabContent(host, state) { pageHost, pageState ->
                    PageContent(pageHost, pageState, padding, pageModifier)
                } else {
                    PageContent(host, state, padding, pageModifier)
                }
            }
        }
        val feedback = state.extras().feedback()
        if (!feedback.isNullOrBlank()) Box(Modifier.fillMaxSize().padding(padding).padding(12.dp),
            contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            Card { Text(feedback, Modifier.padding(12.dp), color = if (state.extras().feedbackSuccess())
                MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error) }
        }
        CropDialog(host, state)
    }
}

@Composable
private fun PageContent(host: MiuixHost, state: MiuixHost.State, padding: PaddingValues, modifier: Modifier) {
    when (state.route().page()) {
        "world_chat", "private_chat" -> MessagesPage(host, state, padding, modifier)
        "settings", "settings_section" -> SettingsPage(host, state, padding, modifier)
        "profile", "profile_detail" -> ProfilePage(host, state, padding, modifier)
        else -> ConversationsPage(host, state, padding, modifier)
    }
}

/** The original feed cycle also changes the glyph and tint, not just the visible rows. */
@Composable
private fun ChatFilterButton(host: MiuixHost, state: MiuixHost.State) {
    val mode = state.extras().worldFilter() ?: "ALL"
    val tint = if (mode == "ALL") MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.primary
    IconButton(onClick = { host.action(Command.FILTER) }, modifier = Modifier.testTag("chat-filter")) {
        AnimatedContent(targetState = mode, label = "Feed filter",
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) }) { selected ->
            val icon = when (selected) {
                "SYSTEM" -> AtomChatIcons.Announcement
                "PLAYERS" -> AtomChatIcons.Player
                else -> AtomChatIcons.Filter
            }
            Icon(icon, host.label("atomchat.chat.filter." + selected.lowercase()), tint = tint,
                modifier = Modifier.size(24.dp).testTag("chat-filter-" + selected.lowercase()))
        }
    }
}

