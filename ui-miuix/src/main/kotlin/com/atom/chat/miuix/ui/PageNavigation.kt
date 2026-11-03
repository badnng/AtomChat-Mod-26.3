package com.atom.chat.miuix.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import top.yukonga.miuix.kmp.squircle.squircleClip

internal data class PageIdentity(val page: String, val target: String, val section: String) : java.io.Serializable

internal fun MiuixHost.State.pageIdentity() = route().let { PageIdentity(it.page(), it.targetKey(), it.section()) }

/** Owns the outgoing snapshot so its rows never read the incoming route's domain state. */
private class PageSnapshot(initialState: MiuixHost.State) {
    var state by mutableStateOf(initialState)
}

private val ROOT_SHELL = PageIdentity("root_shell", "", "")

internal fun MiuixHost.State.isRootTab() = !route().canGoBack() &&
    route().page() in setOf("chat_list", "profile", "settings")

/** Root tabs share one scaffold; only secondary routes become page navigation layers. */
@Composable
internal fun PageNavigation(host: MiuixHost, state: MiuixHost.State,
                            content: @Composable (MiuixHost, MiuixHost.State) -> Unit) {
    NavigationContent(host, state, rootTabs = false, content)
}

/** A small content fade leaves the root scaffold and native tab indicator in place. */
@Composable
internal fun RootTabContent(host: MiuixHost, state: MiuixHost.State,
                            content: @Composable (MiuixHost, MiuixHost.State) -> Unit) {
    NavigationContent(host, state, rootTabs = true, content)
}

@Composable
private fun NavigationContent(host: MiuixHost, state: MiuixHost.State, rootTabs: Boolean,
                              content: @Composable (MiuixHost, MiuixHost.State) -> Unit) {
    val currentPage = if (!rootTabs && state.isRootTab()) ROOT_SHELL else state.pageIdentity()
    // AnimatedContent may retain its content lambda when the shell key is unchanged.
    // Publish the live snapshot before entering it so same-shell route changes invalidate
    // consumers without replacing the scaffold or its remembered native controls.
    val snapshots = remember { mutableMapOf<PageIdentity, PageSnapshot>() }
    snapshots.getOrPut(currentPage) { PageSnapshot(state) }.state = state
    val navigation = host.navigation()
    val navigationStyle = host.pageNavigationStyle()
    val latestPage by rememberUpdatedState(currentPage)
    val transition = updateTransition(currentPage, label = "Miuix page navigation")
    val running = transition.currentState != transition.targetState || transition.isRunning
    val savedPages = rememberSaveableStateHolder()
    LaunchedEffect(navigation.sequence(), running) {
        val rootMotion = navigation.motion().startsWith("ROOT_")
        if (!running && rootMotion == rootTabs) host.navigationTransitionFinished(navigation.sequence())
    }
    transition.AnimatedContent(
        modifier = Modifier.fillMaxSize().testTag(when {
            rootTabs && running -> "miuix-root-tabs-running"
            rootTabs -> "miuix-root-tabs-settled"
            running -> "miuix-navigation-running"
            else -> "miuix-navigation-settled"
        }),
        transitionSpec = {
            if (!state.animations() || navigation.motion() == "NONE") {
                EnterTransition.None togetherWith ExitTransition.None
            } else if (rootTabs) {
                fadeIn(tween(160, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(160, easing = FastOutSlowInEasing))
            } else if (navigationStyle == "ZOOM") {
                // Preserve the domain's serial shrink-out, then enlarged settle-in.
                // Keeping the enter delayed avoids stacking two translucent pages.
                ((fadeIn(tween(260, delayMillis = 180, easing = FastOutSlowInEasing)) +
                    scaleIn(tween(260, delayMillis = 180, easing = FastOutSlowInEasing), initialScale = 1.1f)) togetherWith
                    (fadeOut(tween(180, easing = FastOutSlowInEasing)) +
                        scaleOut(tween(180, easing = FastOutSlowInEasing), targetScale = .9f)))
                    .using(SizeTransform(clip = false))
            } else when (navigation.motion()) {
                "PUSH" -> (slideInHorizontally(tween(500, easing = FastOutSlowInEasing)) { it } togetherWith
                    (slideOutHorizontally(tween(500, easing = FastOutSlowInEasing)) { -it / 4 } +
                        fadeOut(tween(500), targetAlpha = .9f)))
                "POP" -> ((slideInHorizontally(tween(500, easing = FastOutSlowInEasing)) { -it / 4 } +
                    fadeIn(tween(500), initialAlpha = .9f)) togetherWith
                    slideOutHorizontally(tween(500, easing = FastOutSlowInEasing)) { it })
                    .apply { targetContentZIndex = -1f }
                else -> EnterTransition.None togetherWith ExitTransition.None
            }.using(SizeTransform(clip = false))
        },
        contentKey = { it },
    ) { page ->
        val snapshot = remember(page) { snapshots.getOrPut(page) { PageSnapshot(state) } }
        DisposableEffect(page) { onDispose { snapshots.remove(page) } }
        val interactive = page == currentPage
        val pageHost = remember(host, page) {
            NavigationPageHost(host, { snapshot.state }) { page == latestPage }
        }
        val focus = LocalFocusManager.current
        var ownsFocus by remember { mutableStateOf(false) }
        LaunchedEffect(interactive) { if (!interactive && ownsFocus) focus.clearFocus(force = true) }
        savedPages.SaveableStateProvider(page) {
            Box(Modifier.fillMaxSize()
                // The fixed panel clip cannot round a page edge translated into
                // the panel during POP. Move each secondary page's shared shape
                // with its own content; root-tab fades keep their mounted shell.
                .then(if (rootTabs) Modifier else Modifier.squircleClip(
                    snapshot.state.appearance().cornerRadius().coerceAtLeast(0f).dp))
                .focusProperties { canFocus = interactive }
                .onFocusChanged { ownsFocus = it.hasFocus }.focusGroup()
                .onPreviewKeyEvent { !interactive }
                .pointerInput(interactive) {
                    if (!interactive) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})) {
                content(pageHost, snapshot.state)
            }
        }
    }
}

/** Exit-layer disposal cannot unfocus the new page's composer or mutate its live stores. */
internal class NavigationPageHost(private val delegate: MiuixHost,
                                  private val state: () -> MiuixHost.State,
                                  private val interactive: () -> Boolean) : MiuixHost by delegate {
    override fun snapshot() = state()
    // Java interface defaults are not forwarded by Kotlin delegation. Nested root
    // content must observe the host's actual motion instead of the default NONE.
    override fun navigation() = delegate.navigation()
    override fun pageNavigationStyle() = delegate.pageNavigationStyle()
    override fun pokeTime(playerKey: String) = delegate.pokeTime(playerKey)
    override fun hoverText(interaction: String) = delegate.hoverText(interaction)
    override fun uiMetrics() = delegate.uiMetrics()
    override fun uiPalette() = delegate.uiPalette()
    override fun kaomoji() = delegate.kaomoji()
    override fun perform(action: MiuixHost.Action) { if (interactive()) delegate.perform(action) }
    override fun setText(text: String, cursor: Int, anchor: Int) {
        if (interactive()) delegate.setText(text, cursor, anchor)
    }
    override fun requestTextInput(focused: Boolean, x: Float, y: Float, width: Float, height: Float) {
        if (interactive()) delegate.requestTextInput(focused, x, y, width, height)
    }
    override fun composerFocusChanged(focused: Boolean) { if (interactive()) delegate.composerFocusChanged(focused) }
    override fun writeClipboard(text: String) { if (interactive()) delegate.writeClipboard(text) }
    override fun navigationTransitionFinished(sequence: Long) {
        if (interactive()) delegate.navigationTransitionFinished(sequence)
    }
}
