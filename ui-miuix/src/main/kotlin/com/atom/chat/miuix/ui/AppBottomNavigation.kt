package com.atom.chat.miuix.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atom.chat.miuix.api.MiuixHost
import com.atom.chat.miuix.api.MiuixHost.Command
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One root navigation model, presented with the selected upstream Miuix bar. */
@Composable
internal fun BottomNavigation(host: MiuixHost, state: MiuixHost.State, backdrop: LayerBackdrop, blur: Boolean) {
    val focus = LocalFocusManager.current
    val select: (String) -> Unit = { page ->
        focus.clearFocus(force = true)
        host.action(Command.ROOT, page)
    }
    val tabs = listOf(
        Triple("chat_list", host.label("atomchat.tab.chat"), MiuixIcons.Messages),
        Triple("profile", host.label("atomchat.page.profile.title"), MiuixIcons.ContactsCircle),
        Triple("settings", host.label("atomchat.tab.settings"), MiuixIcons.Settings),
    )
    val tags = listOf("nav-chats", "nav-profile", "nav-settings")
    val modifier = Modifier.progressiveChatGlass(backdrop, bottom = true, enabled = blur, opacity = state.appearance().opacity())
    if (state.navigationStyle() == "FLOATING") {
        // Align the capsule ends with the start of the outer panel's corner arcs.
        val inset = state.appearance().cornerRadius().coerceAtLeast(0f).dp
        Box(Modifier.fillMaxWidth().padding(horizontal = inset).testTag("miuix-root-navigation-bar")) {
            // Match Hypernotesuper's standard Miuix floating bar: the page backdrop
            // already contains the mixed world/wallpaper, so keep its tint at 60%.
            // FloatingNavigationBar applies this modifier after its own background;
            // clear that fill and let upstream Highlight paint the glass edge.
            val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
            val capsule = if (blur) Modifier.fillMaxWidth().textureBlur(
                backdrop = backdrop,
                shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius),
                blurRadius = 25f,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surfaceContainer
                        .copy(alpha = .6f))),
                ),
                highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
            ) else Modifier.fillMaxWidth()
            FloatingNavigationBar(modifier = capsule.testTag("floating-navigation-bar"),
                color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                showDivider = !blur, defaultWindowInsetsPadding = false) {
                Row(Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { index, tab ->
                        FloatingNavigationBarItem(selected = state.route().page() == tab.first,
                            onClick = { select(tab.first) }, icon = tab.third, label = tab.second,
                            modifier = Modifier.weight(1f).testTag(tags[index]))
                    }
                }
            }
        }
    } else {
        val mode = when (state.navigationStyle()) {
            "ICON_ONLY" -> NavigationBarDisplayMode.IconOnly
            "SELECTED_LABEL" -> NavigationBarDisplayMode.IconWithSelectedLabel
            else -> NavigationBarDisplayMode.IconAndText
        }
        NavigationBar(modifier = modifier.testTag("miuix-root-navigation-bar"),
            color = MiuixTheme.colorScheme.surface.copy(alpha = if (blur) .72f else 1f), showDivider = true,
            defaultWindowInsetsPadding = false, mode = mode) {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(selected = state.route().page() == tab.first,
                    onClick = { select(tab.first) }, icon = tab.third, label = tab.second,
                    modifier = Modifier.testTag(tags[index]))
            }
        }
    }
}
